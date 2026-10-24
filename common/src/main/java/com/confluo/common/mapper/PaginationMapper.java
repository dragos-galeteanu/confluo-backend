package com.confluo.common.mapper;

import com.confluo.common.dto.Paginated;
import com.confluo.common.dto.PaginationMetaData;
import com.confluo.common.entity.BaseEntity;
import com.confluo.common.exception.InvalidFilterException;
import com.confluo.common.filter.FilterSpecificationBuilder;
import com.confluo.common.filter.PaginationFilter;
import com.confluo.common.repository.BaseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.mapping.PropertyReferenceException;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Pagination mapper class with utility methods for converting between pagination filters and Spring Data pagination objects.
 */
public class PaginationMapper {

    /** logs the listings that were rejected, which the client only sees as a 400 */
    private static final Logger log = LoggerFactory.getLogger(PaginationMapper.class);

    /** the prefix that marks a sort field as descending */
    private static final String DESCENDING_PREFIX = "-";

    /**
     * Private constructor to prevent instantiation.
     */
    private PaginationMapper() {}

    /**
     * Runs a listing: the filter expression as a {@link Specification}, the paging and sorting as a
     * {@link Pageable}, and the resulting page mapped to response objects. Soft-deleted rows are
     * never returned; rows that are merely disabled are, since disabling is a state the caller can
     * filter on itself.
     * @param repository the repository to read through
     * @param filter pagination, filter, and sort parameters
     * @param toResponse converts one entity to its response object
     * @param <ENTITY> type of the entity
     * @param <RESPONSE> type of the response object
     * @return the paginated response
     * @throws InvalidFilterException if the filter or sort expression is malformed or names a field
     *         the entity does not have
     */
    public static <ENTITY extends BaseEntity, RESPONSE> Paginated<RESPONSE> findAll(
            BaseRepository<ENTITY> repository, PaginationFilter filter, Function<ENTITY, RESPONSE> toResponse) {
        Specification<ENTITY> specification = Specification
                .where(PaginationMapper.<ENTITY>notDeleted())
                .and(FilterSpecificationBuilder.build(filter.filter()));

        try {
            Page<ENTITY> page = repository.findAll(specification, toPageable(filter));
            return toPaginated(page, toResponse);
        } catch (PropertyReferenceException | InvalidDataAccessApiUsageException e) {
            // An unknown field only shows up once the query is built, which happens inside findAll
            log.warn("Rejected listing [filter={}, sort={}, reason={}]",
                    filter.filter(), filter.sort(), e.getMessage());
            throw new InvalidFilterException("Unknown field in the filter or sort expression");
        }
    }

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
     * are not checked here: an unknown one is only recognised when the query is built, and
     * {@link #findAll} turns that into an {@link InvalidFilterException}.
     * @param sort the sort expression, nullable
     * @return the sort, unsorted when nothing was asked for
     * @throws InvalidFilterException if a field in the expression is empty
     */
    static Sort toSort(String sort) {
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

    /**
     * Restricts a listing to the rows that have not been soft-deleted.
     * @param <ENTITY> type of the entity
     * @return the specification
     */
    private static <ENTITY extends BaseEntity> Specification<ENTITY> notDeleted() {
        return (root, query, criteriaBuilder) -> criteriaBuilder.isNull(root.get("deletedAt"));
    }
}
