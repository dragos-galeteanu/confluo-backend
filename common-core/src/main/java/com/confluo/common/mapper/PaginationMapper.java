package com.confluo.common.mapper;

import com.confluo.common.dto.Paginated;
import com.confluo.common.dto.PaginationMetaData;
import com.confluo.common.exception.InvalidFilterException;
import com.confluo.common.filter.PaginationFilter;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Pagination mapper class with utility methods for converting between pagination filters and Spring Data pagination objects.
 *
 * <p>Everything here is independent of where a resource is stored. Running a listing is not, because
 * the filter expression becomes a query the store understands; that lives in each store's own
 * mapper - {@code JpaPaginationMapper} and {@code MongoPaginationMapper} - both of which come back
 * here for the paging, the sorting and the response.</p>
 */
public class PaginationMapper {

    /** the prefix that marks a sort field as descending */
    private static final String DESCENDING_PREFIX = "-";

    /**
     * Private constructor to prevent instantiation.
     */
    private PaginationMapper() {}

    /**
     * Converts the paging and sorting parameters into a {@link Pageable}, translating the 1-based
     * page number the API exposes into the 0-based index Spring Data expects.
     *
     * <p>{@link PaginationFilter} applies its own defaults, so neither value is ever null here. Both
     * are still checked, because a service may be called from outside a web request - from a message
     * consumer, or from a test - where the bean validation on the record never ran.</p>
     *
     * @param filter pagination, filter, and sort parameters
     * @return the pageable
     * @throws InvalidFilterException if the page or page size is below 1
     */
    public static Pageable toPageable(PaginationFilter filter) {
        if (filter.page() < 1) {
            throw new InvalidFilterException("Page must be at least 1, was " + filter.page());
        }
        if (filter.pageSize() < 1) {
            throw new InvalidFilterException("Page size must be at least 1, was " + filter.pageSize());
        }
        return PageRequest.of(filter.page() - 1, filter.pageSize(), toSort(filter.sort()));
    }

    /**
     * Converts a page of entities into the paginated response the controllers return.
     * @param page the page of entities
     * @param toResponse converts one entity to its response object
     * @param <ENTITY> type of the entity
     * @param <RESPONSE> type of the response object
     * @return the paginated response
     */
    public static <ENTITY, RESPONSE> Paginated<RESPONSE> toPaginated(
            Page<ENTITY> page, Function<ENTITY, RESPONSE> toResponse) {
        List<RESPONSE> data = page.getContent().stream()
                .map(toResponse)
                .toList();
        return new Paginated<>(data, toMetaData(page));
    }

    /**
     * Builds the metadata describing a page. The page itself is the authority on how many items came
     * back, and its index is converted to the 1-based number the API exposes.
     * @param page the page
     * @param <ENTITY> type of the page content
     * @return the pagination metadata
     */
    public static <ENTITY> PaginationMetaData toMetaData(Page<ENTITY> page) {
        return new PaginationMetaData(
                page.getTotalElements(),
                page.getSize(),
                page.getNumber() + 1,
                page.getTotalPages());
    }

    /**
     * Parses the comma-separated sort expression, where a '-' prefix means descending. Field names
     * are not checked here: an unknown one is only recognised when the query is built, and each
     * store's mapper turns that into an {@link InvalidFilterException}.
     * @param sort the sort expression, nullable
     * @return the sort, unsorted when nothing was asked for
     * @throws InvalidFilterException if a field in the expression is empty
     */
    public static Sort toSort(String sort) {
        if (sort == null || sort.isBlank()) {
            return Sort.unsorted();
        }

        List<Sort.Order> orders = new ArrayList<>();
        for (String token : sort.split(",")) {
            String trimmed = token.trim();
            if (trimmed.isEmpty()) {
                continue;
            }

            boolean descending = trimmed.startsWith(DESCENDING_PREFIX);
            String field = descending ? trimmed.substring(DESCENDING_PREFIX.length()).trim() : trimmed;
            if (field.isEmpty()) {
                throw new InvalidFilterException("Malformed sort field in '%s'".formatted(sort));
            }
            orders.add(descending ? Sort.Order.desc(field) : Sort.Order.asc(field));
        }

        return orders.isEmpty() ? Sort.unsorted() : Sort.by(orders);
    }
}
