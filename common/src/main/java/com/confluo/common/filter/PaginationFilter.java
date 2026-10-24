package com.confluo.common.filter;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * Filter for pagination, filtering, and sorting parameters in API requests.
 * @param page 1-based page number, defaults to {@value #DEFAULT_PAGE} when not supplied
 * @param pageSize number of items per page, defaults to {@value #DEFAULT_PAGE_SIZE} when not supplied
 * @param filter filter expression, e.g. "companyId==8a1e...;createdAt=gt=2024-01-01T00:00:00Z", nullable;
 *               see {@link FilterSpecificationBuilder} for the full syntax
 * @param sort comma-separated sort fields, '-' prefix for descending, e.g. "-createdAt,name", nullable
 */
public record PaginationFilter(
        @Min(1)
        Integer page,

        @Min(value = 1, message = "Page size must be at least 1")
        @Max(value = 100, message = "Page size cannot exceed 100")
        Integer pageSize,

        String filter,

        String sort
) {

    /** the page returned when the request does not ask for one */
    public static final int DEFAULT_PAGE = 1;

    /** the number of items per page returned when the request does not ask for one */
    public static final int DEFAULT_PAGE_SIZE = 10;

    /**
     * Applies the defaults for the paging parameters, so that the rest of the pagination machinery -
     * {@link com.confluo.common.mapper.PaginationMapper#toPageable} in particular, which unboxes both - never sees a null.
     * Requests that omit {@code page} and {@code pageSize} entirely are the common case, not an error.
     */
    public PaginationFilter {
        page = page == null ? DEFAULT_PAGE : page;
        pageSize = pageSize == null ? DEFAULT_PAGE_SIZE : pageSize;
    }
}
