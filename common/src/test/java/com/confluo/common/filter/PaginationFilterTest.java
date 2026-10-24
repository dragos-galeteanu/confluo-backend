package com.confluo.common.filter;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link PaginationFilter}: the defaults its compact constructor applies, and the bean
 * validation the controllers rely on.
 */
class PaginationFilterTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void startValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void stopValidator() {
        factory.close();
    }

    private static Set<String> messages(PaginationFilter filter) {
        return validator.validate(filter).stream()
                .map(ConstraintViolation::getMessage)
                .collect(java.util.stream.Collectors.toSet());
    }

    @Test
    void anEmptyRequestGetsTheDefaults() {
        PaginationFilter filter = new PaginationFilter(null, null, null, null);

        assertThat(filter.page()).isEqualTo(PaginationFilter.DEFAULT_PAGE);
        assertThat(filter.pageSize()).isEqualTo(PaginationFilter.DEFAULT_PAGE_SIZE);
        assertThat(filter.filter()).isNull();
        assertThat(filter.sort()).isNull();
    }

    @Test
    void theDefaultsAreTheDocumentedValues() {
        assertThat(PaginationFilter.DEFAULT_PAGE).isEqualTo(1);
        assertThat(PaginationFilter.DEFAULT_PAGE_SIZE).isEqualTo(10);
    }

    @Test
    void suppliedPagingValuesAreKept() {
        PaginationFilter filter = new PaginationFilter(4, 50, "name==alpha", "-createdAt");

        assertThat(filter.page()).isEqualTo(4);
        assertThat(filter.pageSize()).isEqualTo(50);
        assertThat(filter.filter()).isEqualTo("name==alpha");
        assertThat(filter.sort()).isEqualTo("-createdAt");
    }

    @Test
    void onlyTheMissingPagingValueIsDefaulted() {
        assertThat(new PaginationFilter(7, null, null, null).pageSize())
                .isEqualTo(PaginationFilter.DEFAULT_PAGE_SIZE);
        assertThat(new PaginationFilter(null, 7, null, null).page())
                .isEqualTo(PaginationFilter.DEFAULT_PAGE);
    }

    @Test
    void aFilterWithTheDefaultsIsValid() {
        assertThat(validator.validate(new PaginationFilter(null, null, null, null))).isEmpty();
    }

    @Test
    void theLargestAllowedPageSizeIsValid() {
        assertThat(validator.validate(new PaginationFilter(1, 100, null, null))).isEmpty();
    }

    @Test
    void aPageBelowOneIsRejected() {
        assertThat(validator.validate(new PaginationFilter(0, 10, null, null))).isNotEmpty();
    }

    @Test
    void aPageSizeBelowOneIsRejectedWithItsOwnMessage() {
        assertThat(messages(new PaginationFilter(1, 0, null, null)))
                .contains("Page size must be at least 1");
    }

    @Test
    void aPageSizeAboveTheLimitIsRejectedWithItsOwnMessage() {
        assertThat(messages(new PaginationFilter(1, 101, null, null)))
                .contains("Page size cannot exceed 100");
    }

    @Test
    void theFilterAndSortExpressionsAreNotValidatedHere() {
        assertThat(validator.validate(new PaginationFilter(1, 10, "!!! not rsql", "---"))).isEmpty();
    }
}
