package com.confluo.common.filter;

import com.confluo.common.exception.InvalidFilterException;
import cz.jirutka.rsql.parser.RSQLParser;
import cz.jirutka.rsql.parser.RSQLParserException;
import cz.jirutka.rsql.parser.ast.AndNode;
import cz.jirutka.rsql.parser.ast.ComparisonNode;
import cz.jirutka.rsql.parser.ast.ComparisonOperator;
import cz.jirutka.rsql.parser.ast.LogicalNode;
import cz.jirutka.rsql.parser.ast.NoArgRSQLVisitorAdapter;
import cz.jirutka.rsql.parser.ast.Node;
import cz.jirutka.rsql.parser.ast.OrNode;
import cz.jirutka.rsql.parser.ast.RSQLOperators;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Translates the filter expression carried by a {@link PaginationFilter} into a
 * {@link Specification}, which {@link com.confluo.common.mapper.PaginationMapper#findAll} runs
 * against the repository.
 *
 * <p>The syntax is RSQL. A comparison is {@code field<operator>value}; comparisons are joined with
 * {@code ;} for AND and {@code ,} for OR, and parentheses group them. The operators are the RSQL
 * defaults:</p>
 *
 * <ul>
 *     <li>{@code ==} equal, {@code !=} not equal</li>
 *     <li>{@code =gt=} / {@code >}, {@code =ge=} / {@code >=}, {@code =lt=} / {@code <},
 *         {@code =le=} / {@code <=}</li>
 *     <li>{@code =in=(a,b)} in, {@code =out=(a,b)} not in</li>
 * </ul>
 *
 * <p>A field may be a path across to-one associations, as in {@code company.id}. Values are
 * converted to the type of the attribute they are compared against, and the literal {@code null}
 * with {@code ==} or {@code !=} becomes an IS NULL / IS NOT NULL check, which the nullable
 * {@code disabledAt} and {@code deletedAt} markers need. Anything the parser or the conversion
 * rejects is an {@link InvalidFilterException}, which the global handler reports as a 400.</p>
 */
public final class FilterSpecificationBuilder {

    /** the parser is immutable and thread-safe once built, so one instance serves every request */
    private static final RSQLParser PARSER = new RSQLParser();

    /** the value that turns an equality comparison into a null check */
    private static final String NULL_LITERAL = "null";

    /**
     * Private constructor to prevent instantiation.
     */
    private FilterSpecificationBuilder() {}

    /**
     * Builds the specification for a filter expression.
     * @param filter the filter expression, nullable
     * @param <ENTITY> type of the entity being filtered
     * @return the specification, or null when no filter was asked for - which Spring Data reads as
     *         "no restriction"
     * @throws InvalidFilterException if the expression is malformed, names an unknown field, or
     *         compares a field against a value of the wrong type
     */
    public static <ENTITY> Specification<ENTITY> build(String filter) {
        if (filter == null || filter.isBlank()) {
            return null;
        }

        Node root;
        try {
            root = PARSER.parse(filter);
        } catch (RSQLParserException e) {
            throw new InvalidFilterException("Malformed filter expression: " + filter);
        }

        return root.accept(new SpecificationVisitor<>());
    }

    /**
     * Walks the parsed expression, turning each node into a specification.
     * @param <ENTITY> type of the entity being filtered
     */
    private static final class SpecificationVisitor<ENTITY>
            extends NoArgRSQLVisitorAdapter<Specification<ENTITY>> {

        /**
         * Visits a group of comparisons joined with ';'.
         * @param node the AND node
         * @return the specification matching every child
         */
        @Override
        public Specification<ENTITY> visit(AndNode node) {
            return combine(node, true);
        }

        /**
         * Visits a group of comparisons joined with ','.
         * @param node the OR node
         * @return the specification matching any child
         */
        @Override
        public Specification<ENTITY> visit(OrNode node) {
            return combine(node, false);
        }

        /**
         * Visits a single comparison. The predicate is built lazily, when the query is: the root and
         * the criteria builder only exist once the repository runs the specification.
         * @param node the comparison node
         * @return the specification for the comparison
         */
        @Override
        public Specification<ENTITY> visit(ComparisonNode node) {
            return (root, query, criteriaBuilder) -> toPredicate(node, root, criteriaBuilder);
        }

        /**
         * Folds the children of a logical node together.
         * @param node the logical node
         * @param and true to join the children with AND, false for OR
         * @return the combined specification
         */
        private Specification<ENTITY> combine(LogicalNode node, boolean and) {
            Specification<ENTITY> combined = null;
            for (Node child : node) {
                Specification<ENTITY> childSpecification = child.accept(this);
                if (combined == null) {
                    combined = childSpecification;
                } else {
                    combined = and ? combined.and(childSpecification) : combined.or(childSpecification);
                }
            }
            return combined;
        }
    }

    /**
     * Turns a single comparison into a predicate.
     * @param node the comparison
     * @param root the query root
     * @param criteriaBuilder the criteria builder
     * @return the predicate
     */
    private static Predicate toPredicate(ComparisonNode node, Root<?> root, CriteriaBuilder criteriaBuilder) {
        String selector = node.getSelector();
        Path<?> path = resolvePath(root, selector);
        ComparisonOperator operator = node.getOperator();
        List<String> arguments = node.getArguments();
        String first = arguments.get(0);

        // A comparison against the null literal is a null check, not a value comparison
        if (NULL_LITERAL.equalsIgnoreCase(first)) {
            if (RSQLOperators.EQUAL.equals(operator)) {
                return criteriaBuilder.isNull(path);
            }
            if (RSQLOperators.NOT_EQUAL.equals(operator)) {
                return criteriaBuilder.isNotNull(path);
            }
            throw new InvalidFilterException(
                    "The null literal only works with '==' or '!=', used with '%s' on '%s'"
                            .formatted(operator.getSymbol(), selector));
        }

        if (RSQLOperators.IN.equals(operator) || RSQLOperators.NOT_IN.equals(operator)) {
            List<Object> values = arguments.stream()
                    .map(argument -> convert(argument, path, selector))
                    .toList();
            Predicate in = path.in(values);
            return RSQLOperators.IN.equals(operator) ? in : criteriaBuilder.not(in);
        }

        Object value = convert(first, path, selector);
        if (RSQLOperators.EQUAL.equals(operator)) {
            return criteriaBuilder.equal(path, value);
        }
        if (RSQLOperators.NOT_EQUAL.equals(operator)) {
            return criteriaBuilder.notEqual(path, value);
        }
        return toOrderingPredicate(operator, path, value, selector, criteriaBuilder);
    }

    /**
     * Builds one of the four ordering comparisons. The raw {@link Comparable} is unavoidable here:
     * the attribute type is only known at runtime, and the Criteria API asks for a comparable
     * expression and a matching bound.
     * @param operator the comparison operator
     * @param path the attribute being compared
     * @param value the converted value
     * @param selector the field name, for the error message
     * @param criteriaBuilder the criteria builder
     * @return the predicate
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Predicate toOrderingPredicate(ComparisonOperator operator, Path<?> path, Object value,
                                                 String selector, CriteriaBuilder criteriaBuilder) {
        if (!(value instanceof Comparable comparable)) {
            throw new InvalidFilterException(
                    "Field '%s' cannot be ordered with '%s'".formatted(selector, operator.getSymbol()));
        }

        Expression<Comparable> expression = (Expression<Comparable>) path;
        if (RSQLOperators.GREATER_THAN.equals(operator)) {
            return criteriaBuilder.greaterThan(expression, comparable);
        }
        if (RSQLOperators.GREATER_THAN_OR_EQUAL.equals(operator)) {
            return criteriaBuilder.greaterThanOrEqualTo(expression, comparable);
        }
        if (RSQLOperators.LESS_THAN.equals(operator)) {
            return criteriaBuilder.lessThan(expression, comparable);
        }
        if (RSQLOperators.LESS_THAN_OR_EQUAL.equals(operator)) {
            return criteriaBuilder.lessThanOrEqualTo(expression, comparable);
        }
        throw new InvalidFilterException("Unsupported filter operator '%s'".formatted(operator.getSymbol()));
    }

    /**
     * Walks a possibly dotted field name down from the query root. Only to-one associations are
     * supported; filtering across a collection would need a join and is not offered.
     * @param root the query root
     * @param selector the field name, e.g. "companyId" or "company.id"
     * @return the path to the attribute
     */
    private static Path<?> resolvePath(Root<?> root, String selector) {
        Path<?> path = root;
        try {
            for (String segment : selector.split("\\.")) {
                path = path.get(segment);
            }
        } catch (IllegalArgumentException e) {
            throw new InvalidFilterException("Unknown filter field '%s'".formatted(selector));
        }
        return path;
    }

    /**
     * Converts one argument of the expression to the type of the attribute it is compared against.
     * @param raw the argument as written in the expression
     * @param path the attribute being compared
     * @param selector the field name, for the error message
     * @return the converted value
     */
    private static Object convert(String raw, Path<?> path, String selector) {
        Class<?> type = path.getJavaType();
        try {
            if (type == String.class) {
                return raw;
            }
            if (type == UUID.class) {
                return UUID.fromString(raw);
            }
            if (type == Instant.class) {
                return Instant.parse(raw);
            }
            if (type == LocalDate.class) {
                return LocalDate.parse(raw);
            }
            if (type == Boolean.class || type == boolean.class) {
                return toBoolean(raw);
            }
            if (type == Integer.class || type == int.class) {
                return Integer.valueOf(raw);
            }
            if (type == Long.class || type == long.class) {
                return Long.valueOf(raw);
            }
            if (type == Double.class || type == double.class) {
                return Double.valueOf(raw);
            }
            if (type == BigDecimal.class) {
                return new BigDecimal(raw);
            }
            if (type.isEnum()) {
                return toEnum(type, raw);
            }
            return raw;
        } catch (RuntimeException e) {
            throw new InvalidFilterException(
                    "Invalid value '%s' for filter field '%s'".formatted(raw, selector));
        }
    }

    /**
     * Parses a boolean strictly, so that a typo is reported rather than read as false.
     * @param raw the argument as written in the expression
     * @return the boolean value
     */
    private static Boolean toBoolean(String raw) {
        if ("true".equalsIgnoreCase(raw)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(raw)) {
            return Boolean.FALSE;
        }
        throw new IllegalArgumentException(raw);
    }

    /**
     * Resolves an enum constant by name, ignoring case.
     * @param type the enum type of the attribute
     * @param raw the argument as written in the expression
     * @return the enum constant
     */
    private static Object toEnum(Class<?> type, String raw) {
        for (Object constant : type.getEnumConstants()) {
            if (((Enum<?>) constant).name().equalsIgnoreCase(raw)) {
                return constant;
            }
        }
        throw new IllegalArgumentException(raw);
    }
}
