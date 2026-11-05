package com.confluo.common.mongo.filter;

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
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.query.Criteria;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Translates the filter expression carried by a {@link com.confluo.common.filter.PaginationFilter}
 * into a MongoDB {@link Criteria}, which
 * {@link com.confluo.common.mongo.mapper.MongoPaginationMapper#findAll} runs.
 *
 * <p>The syntax is the same RSQL the relational builder accepts, so a caller writes one filter
 * expression whichever store answers it: {@code ==} and {@code !=}, {@code =gt=} / {@code =ge=} /
 * {@code =lt=} / {@code =le=}, {@code =in=} and {@code =out=}, joined with {@code ;} for AND and
 * {@code ,} for OR, and the literal {@code null} for a presence check.</p>
 *
 * <p>One thing differs, and it matters. The relational builder asks the mapping what type an
 * attribute has and converts the argument to it. A document has no schema to ask, so the type is
 * inferred from how the argument is written: {@code true} is a boolean, a UUID-shaped string is a
 * {@link UUID}, a 24-character hex string is an {@link ObjectId} - which is what a document's own
 * {@code id} is - an ISO-8601 instant is an {@link Instant}, digits are a number, and anything else
 * stays a string. A field holding the string {@code "12345"} therefore will not match
 * {@code field==12345}. Where that is a problem, store the value in its natural type.</p>
 */
public final class FilterCriteriaBuilder {

    /** the parser is immutable and thread-safe once built, so one instance serves every request */
    private static final RSQLParser PARSER = new RSQLParser();

    /** the value that turns an equality comparison into a null check */
    private static final String NULL_LITERAL = "null";

    /** the shape of a UUID argument */
    private static final Pattern UUID_SHAPE =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    /** the shape of an identifier MongoDB generated */
    private static final Pattern OBJECT_ID_SHAPE = Pattern.compile("[0-9a-fA-F]{24}");

    /** the shape of a whole number */
    private static final Pattern INTEGER_SHAPE = Pattern.compile("-?\\d{1,18}");

    /** the shape of a decimal number */
    private static final Pattern DECIMAL_SHAPE = Pattern.compile("-?\\d+\\.\\d+");

    /**
     * Private constructor to prevent instantiation.
     */
    private FilterCriteriaBuilder() {}

    /**
     * Builds the criteria for a filter expression.
     * @param filter the filter expression, nullable
     * @return the criteria, or null when no filter was asked for
     * @throws InvalidFilterException if the expression is malformed or uses an unsupported operator
     */
    public static Criteria build(String filter) {
        if (filter == null || filter.isBlank()) {
            return null;
        }

        Node root;
        try {
            root = PARSER.parse(filter);
        } catch (RSQLParserException e) {
            throw new InvalidFilterException("Malformed filter expression: " + filter);
        }

        return root.accept(new CriteriaVisitor());
    }

    /**
     * Walks the parsed expression, turning each node into criteria.
     */
    private static final class CriteriaVisitor extends NoArgRSQLVisitorAdapter<Criteria> {

        @Override
        public Criteria visit(AndNode node) {
            return combine(node, true);
        }

        @Override
        public Criteria visit(OrNode node) {
            return combine(node, false);
        }

        @Override
        public Criteria visit(ComparisonNode node) {
            return toCriteria(node);
        }

        /**
         * Joins the children of a logical node.
         * @param node the logical node
         * @param and true to join the children with AND, false for OR
         * @return the combined criteria
         */
        private Criteria combine(LogicalNode node, boolean and) {
            List<Criteria> children = new ArrayList<>();
            for (Node child : node) {
                children.add(child.accept(this));
            }

            Criteria[] array = children.toArray(Criteria[]::new);
            return and ? new Criteria().andOperator(array) : new Criteria().orOperator(array);
        }
    }

    /**
     * Turns a single comparison into criteria.
     * @param node the comparison
     * @return the criteria
     */
    private static Criteria toCriteria(ComparisonNode node) {
        String selector = node.getSelector();
        Criteria criteria = Criteria.where(selector);
        ComparisonOperator operator = node.getOperator();
        List<String> arguments = node.getArguments();
        String first = arguments.get(0);

        // A comparison against the null literal is a presence check, not a value comparison
        if (NULL_LITERAL.equalsIgnoreCase(first)) {
            if (RSQLOperators.EQUAL.equals(operator)) {
                return criteria.is(null);
            }
            if (RSQLOperators.NOT_EQUAL.equals(operator)) {
                return criteria.ne(null);
            }
            throw new InvalidFilterException(
                    "The null literal only works with '==' or '!=', used with '%s' on '%s'"
                            .formatted(operator.getSymbol(), selector));
        }

        if (RSQLOperators.IN.equals(operator)) {
            return criteria.in(arguments.stream().map(FilterCriteriaBuilder::inferValue).toList());
        }
        if (RSQLOperators.NOT_IN.equals(operator)) {
            return criteria.nin(arguments.stream().map(FilterCriteriaBuilder::inferValue).toList());
        }

        Object value = inferValue(first);
        if (RSQLOperators.EQUAL.equals(operator)) {
            return criteria.is(value);
        }
        if (RSQLOperators.NOT_EQUAL.equals(operator)) {
            return criteria.ne(value);
        }
        if (RSQLOperators.GREATER_THAN.equals(operator)) {
            return criteria.gt(value);
        }
        if (RSQLOperators.GREATER_THAN_OR_EQUAL.equals(operator)) {
            return criteria.gte(value);
        }
        if (RSQLOperators.LESS_THAN.equals(operator)) {
            return criteria.lt(value);
        }
        if (RSQLOperators.LESS_THAN_OR_EQUAL.equals(operator)) {
            return criteria.lte(value);
        }
        throw new InvalidFilterException("Unsupported filter operator '%s'".formatted(operator.getSymbol()));
    }

    /**
     * Infers the type of an argument from how it is written, since a document carries no schema to
     * consult.
     * @param raw the argument as written in the expression
     * @return the converted value
     */
    static Object inferValue(String raw) {
        if ("true".equalsIgnoreCase(raw)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(raw)) {
            return Boolean.FALSE;
        }
        if (UUID_SHAPE.matcher(raw).matches()) {
            return UUID.fromString(raw);
        }
        // Before the numeric check: an id is hex, and a 24-digit number would otherwise win.
        if (OBJECT_ID_SHAPE.matcher(raw).matches()) {
            return new ObjectId(raw);
        }
        if (INTEGER_SHAPE.matcher(raw).matches()) {
            return Long.valueOf(raw);
        }
        if (DECIMAL_SHAPE.matcher(raw).matches()) {
            return new BigDecimal(raw);
        }
        try {
            return Instant.parse(raw);
        } catch (DateTimeParseException e) {
            return raw;
        }
    }
}
