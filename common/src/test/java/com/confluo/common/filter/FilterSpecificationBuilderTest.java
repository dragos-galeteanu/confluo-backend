package com.confluo.common.filter;

import com.confluo.common.exception.InvalidFilterException;
import com.confluo.common.security.UserRole;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.Mockito.verify;

/**
 * Tests for {@link FilterSpecificationBuilder}. The predicates are built against mocked Criteria API
 * objects: the translation from the expression to the right builder call is what matters here, and it
 * needs no persistence context.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class FilterSpecificationBuilderTest {

    private Root root;
    private CriteriaBuilder criteriaBuilder;
    private Path path;
    private Predicate expected;

    @BeforeEach
    void setUp() {
        root = mock(Root.class);
        criteriaBuilder = mock(CriteriaBuilder.class);
        path = mock(Path.class);
        expected = mock(Predicate.class);
    }

    private Predicate predicateFor(String filter) {
        Specification<Object> specification = FilterSpecificationBuilder.build(filter);
        assertThat(specification).isNotNull();
        return specification.toPredicate(root, mock(CriteriaQuery.class), criteriaBuilder);
    }

    @Test
    void nothingToFilterOnIsNoRestriction() {
        assertThat(FilterSpecificationBuilder.build(null)).isNull();
        assertThat(FilterSpecificationBuilder.build("   ")).isNull();
    }

    @Test
    void aMalformedExpressionIsRejected() {
        assertThatThrownBy(() -> FilterSpecificationBuilder.build("(((companyId"))
                .isInstanceOf(InvalidFilterException.class)
                .hasMessageContaining("Malformed filter expression");
    }

    @Test
    void anEqualityComparesTheValueConvertedToTheAttributeType() {
        UUID companyId = UUID.randomUUID();
        given(root.get("companyId")).willReturn(path);
        given(path.getJavaType()).willReturn((Class) UUID.class);
        given(criteriaBuilder.equal(path, companyId)).willReturn(expected);

        assertThat(predicateFor("companyId==" + companyId)).isSameAs(expected);
    }

    @Test
    void anOrderingComparisonUsesTheConvertedBound() {
        Instant createdAt = Instant.parse("2024-01-01T00:00:00Z");
        given(root.get("createdAt")).willReturn(path);
        given(path.getJavaType()).willReturn((Class) Instant.class);
        given(criteriaBuilder.greaterThan(any(Expression.class), any(Comparable.class))).willReturn(expected);

        assertThat(predicateFor("createdAt=gt=2024-01-01T00:00:00Z")).isSameAs(expected);
        verify(criteriaBuilder).greaterThan(path, createdAt);
    }

    @Test
    void theNullLiteralBecomesANullCheck() {
        given(root.get("disabledAt")).willReturn(path);
        given(criteriaBuilder.isNull(path)).willReturn(expected);

        assertThat(predicateFor("disabledAt==null")).isSameAs(expected);
    }

    @Test
    void theNegatedNullLiteralBecomesANotNullCheck() {
        given(root.get("disabledAt")).willReturn(path);
        given(criteriaBuilder.isNotNull(path)).willReturn(expected);

        assertThat(predicateFor("disabledAt!=null")).isSameAs(expected);
    }

    @Test
    void anInComparisonCollectsEveryArgument() {
        given(root.get("name")).willReturn(path);
        given(path.getJavaType()).willReturn((Class) String.class);
        given(path.in(any(java.util.Collection.class))).willReturn(expected);

        assertThat(predicateFor("name=in=(alpha,beta)")).isSameAs(expected);
        verify(path).in(java.util.List.of("alpha", "beta"));
    }

    @Test
    void aDottedFieldWalksTheAssociation() {
        Path company = mock(Path.class);
        UUID companyId = UUID.randomUUID();
        given(root.get("company")).willReturn(company);
        given(company.get("id")).willReturn(path);
        given(path.getJavaType()).willReturn((Class) UUID.class);
        given(criteriaBuilder.equal(path, companyId)).willReturn(expected);

        assertThat(predicateFor("company.id==" + companyId)).isSameAs(expected);
    }

    @Test
    void bothSidesOfAnAndAreVisited() {
        given(root.get("name")).willReturn(path);
        given(root.get("createdAt")).willReturn(path);
        given(path.getJavaType()).willReturn((Class) String.class);
        given(criteriaBuilder.equal(path, "alpha")).willReturn(mock(Predicate.class));
        given(criteriaBuilder.greaterThan(any(Expression.class), any(Comparable.class)))
                .willReturn(mock(Predicate.class));
        given(criteriaBuilder.and(any(Expression.class), any(Expression.class))).willReturn(expected);

        assertThat(predicateFor("name==alpha;createdAt=gt=beta")).isSameAs(expected);
        verify(root).get("name");
        verify(root).get("createdAt");
    }

    @Test
    void anUnknownFieldIsRejected() {
        given(root.get("bogus")).willThrow(new IllegalArgumentException("Unable to locate attribute"));

        assertThatThrownBy(() -> predicateFor("bogus==1"))
                .isInstanceOf(InvalidFilterException.class)
                .hasMessageContaining("Unknown filter field 'bogus'");
    }

    @Test
    void aValueOfTheWrongTypeIsRejected() {
        given(root.get("companyId")).willReturn(path);
        given(path.getJavaType()).willReturn((Class) UUID.class);

        assertThatThrownBy(() -> predicateFor("companyId==not-a-uuid"))
                .isInstanceOf(InvalidFilterException.class)
                .hasMessageContaining("Invalid value 'not-a-uuid' for filter field 'companyId'");
    }

    @Test
    void aBooleanTypoIsRejectedRatherThanReadAsFalse() {
        given(root.get("active")).willReturn(path);
        given(path.getJavaType()).willReturn((Class) Boolean.class);

        assertThatThrownBy(() -> predicateFor("active==yes"))
                .isInstanceOf(InvalidFilterException.class)
                .hasMessageContaining("Invalid value 'yes'");
    }

    @Test
    void theNullLiteralIsRejectedWithAnOrderingOperator() {
        given(root.get("createdAt")).willReturn(path);

        assertThatThrownBy(() -> predicateFor("createdAt=gt=null"))
                .isInstanceOf(InvalidFilterException.class)
                .hasMessageContaining("only works with '==' or '!='");
    }

    @Test
    void eitherSideOfAnOrIsAccepted() {
        given(root.get("name")).willReturn(path);
        given(path.getJavaType()).willReturn((Class) String.class);
        given(criteriaBuilder.equal(any(Expression.class), any(Object.class))).willReturn(mock(Predicate.class));
        given(criteriaBuilder.or(any(Expression.class), any(Expression.class))).willReturn(expected);

        assertThat(predicateFor("name==alpha,name==beta")).isSameAs(expected);
    }

    @Test
    void anInequalityComparesTheValue() {
        given(root.get("name")).willReturn(path);
        given(path.getJavaType()).willReturn((Class) String.class);
        given(criteriaBuilder.notEqual(path, "alpha")).willReturn(expected);

        assertThat(predicateFor("name!=alpha")).isSameAs(expected);
    }

    @Test
    void aNotInComparisonNegatesTheIn() {
        Predicate in = mock(Predicate.class);
        given(root.get("name")).willReturn(path);
        given(path.getJavaType()).willReturn((Class) String.class);
        given(path.in(any(java.util.Collection.class))).willReturn(in);
        given(criteriaBuilder.not(in)).willReturn(expected);

        assertThat(predicateFor("name=out=(alpha,beta)")).isSameAs(expected);
        verify(path).in(java.util.List.of("alpha", "beta"));
    }

    @Test
    void greaterThanOrEqualIsMapped() {
        given(root.get("name")).willReturn(path);
        given(path.getJavaType()).willReturn((Class) String.class);
        given(criteriaBuilder.greaterThanOrEqualTo(any(Expression.class), any(Comparable.class)))
                .willReturn(expected);

        assertThat(predicateFor("name=ge=alpha")).isSameAs(expected);
        verify(criteriaBuilder).greaterThanOrEqualTo(path, "alpha");
    }

    @Test
    void lessThanIsMapped() {
        given(root.get("name")).willReturn(path);
        given(path.getJavaType()).willReturn((Class) String.class);
        given(criteriaBuilder.lessThan(any(Expression.class), any(Comparable.class))).willReturn(expected);

        assertThat(predicateFor("name=lt=alpha")).isSameAs(expected);
        verify(criteriaBuilder).lessThan(path, "alpha");
    }

    @Test
    void lessThanOrEqualIsMapped() {
        given(root.get("name")).willReturn(path);
        given(path.getJavaType()).willReturn((Class) String.class);
        given(criteriaBuilder.lessThanOrEqualTo(any(Expression.class), any(Comparable.class)))
                .willReturn(expected);

        assertThat(predicateFor("name=le=alpha")).isSameAs(expected);
        verify(criteriaBuilder).lessThanOrEqualTo(path, "alpha");
    }

    /**
     * The argument of a comparison, and the value the criteria builder is handed for it. The last
     * case is a type the converter does not know, which is passed through as the raw string.
     * @return the cases
     */
    static Stream<Arguments> conversions() {
        return Stream.of(
                arguments(String.class, "alpha", "alpha"),
                arguments(Instant.class, "2024-01-01T00:00:00Z", Instant.parse("2024-01-01T00:00:00Z")),
                arguments(LocalDate.class, "2024-05-06", LocalDate.of(2024, 5, 6)),
                arguments(Boolean.class, "true", Boolean.TRUE),
                arguments(boolean.class, "FALSE", Boolean.FALSE),
                arguments(Integer.class, "42", 42),
                arguments(int.class, "42", 42),
                arguments(Long.class, "42", 42L),
                arguments(long.class, "42", 42L),
                arguments(Double.class, "1.5", 1.5d),
                arguments(double.class, "1.5", 1.5d),
                arguments(BigDecimal.class, "10.50", new BigDecimal("10.50")),
                arguments(UserRole.class, "admin", UserRole.Admin),
                arguments(URI.class, "whatever", "whatever"));
    }

    @ParameterizedTest
    @MethodSource("conversions")
    void theValueIsConvertedToTheTypeOfTheAttribute(Class<?> javaType, String raw, Object value) {
        given(root.get("field")).willReturn(path);
        given(path.getJavaType()).willReturn((Class) javaType);
        given(criteriaBuilder.equal(path, value)).willReturn(expected);

        assertThat(predicateFor("field==" + raw)).isSameAs(expected);
    }

    @Test
    void anUnknownEnumConstantIsRejected() {
        given(root.get("role")).willReturn(path);
        given(path.getJavaType()).willReturn((Class) UserRole.class);

        assertThatThrownBy(() -> predicateFor("role==emperor"))
                .isInstanceOf(InvalidFilterException.class)
                .hasMessageContaining("Invalid value 'emperor' for filter field 'role'");
    }
}
