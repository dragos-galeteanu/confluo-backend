package com.confluo.common.dto;

import java.util.List;

/**
 * Paginated response DTO.
 * @param data list of items
 * @param metaData pagination metadata
 * @param <T> type of the items
 */
public record Paginated<T>(
        List<T> data,
        PaginationMetaData metaData
) {
}
