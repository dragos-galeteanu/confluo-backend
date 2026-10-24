package com.confluo.common.dto;

/**
 * Pagination metadata for a paginated response.
 * @param totalCount total number of items
 * @param pageSize number of items per page
 * @param currentPage current page number
 * @param totalPages total number of pages
 */
public record PaginationMetaData(
        Long totalCount,
        Integer pageSize,
        Integer currentPage,
        Integer totalPages
) {
}
