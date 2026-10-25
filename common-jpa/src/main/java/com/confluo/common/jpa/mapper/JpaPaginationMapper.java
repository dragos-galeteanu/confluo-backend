package com.confluo.common.jpa.mapper;

import com.confluo.common.dto.Paginated;
import com.confluo.common.entity.BaseEntity;
import com.confluo.common.exception.InvalidFilterException;
import com.confluo.common.filter.PaginationFilter;
import com.confluo.common.jpa.entity.BaseJpaEntity;
import com.confluo.common.jpa.filter.FilterSpecificationBuilder;
import com.confluo.common.jpa.repository.BaseJpaRepository;
import com.confluo.common.mapper.PaginationMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.mapping.PropertyReferenceException;

import java.util.function.Function;

/**
 * Runs a listing against a relational store: the filter expression as a {@link Specification}, the
 * paging and sorting as a {@link org.springframework.data.domain.Pageable}, and the resulting page
 * mapped to response objects.
 */
public class JpaPaginationMapper {

    /** logs the listings that were rejected, which the client only sees as a 400 */
    private static final Logger log = LoggerFactory.getLogger(JpaPaginationMapper.class);

    /**
     * Private constructor to prevent instantiation.
     */
    private JpaPaginationMapper() {}

    /**
     * Runs a listing. Soft-deleted rows are never returned; rows that are merely disabled are, since
     * disabling is a state the caller can filter on itself.
     * @param repository the repository to read through
     * @param filter pagination, filter, and sort parameters
     * @param toResponse converts one entity to its response object
     * @param <ENTITY> type of the entity
     * @param <RESPONSE> type of the response object
     * @return the paginated response
     * @throws InvalidFilterException if the filter or sort expression is malformed or names a field
     *         the entity does not have
     */
    public static <ENTITY extends BaseJpaEntity, RESPONSE> Paginated<RESPONSE> findAll(
            BaseJpaRepository<ENTITY> repository, PaginationFilter filter,
            Function<ENTITY, RESPONSE> toResponse) {
        Specification<ENTITY> specification = Specification
                .where(JpaPaginationMapper.<ENTITY>notDeleted())
                .and(FilterSpecificationBuilder.build(filter.filter()));

        try {
            Page<ENTITY> page = repository.findAll(specification, PaginationMapper.toPageable(filter));
            return PaginationMapper.toPaginated(page, toResponse);
        } catch (PropertyReferenceException | InvalidDataAccessApiUsageException e) {
            // An unknown field only shows up once the query is built, which happens inside findAll
            log.warn("Rejected listing [filter={}, sort={}, reason={}]",
                    filter.filter(), filter.sort(), e.getMessage());
            throw new InvalidFilterException("Unknown field in the filter or sort expression");
        }
    }

    /**
     * Restricts a listing to the rows that have not been soft-deleted.
     * @param <ENTITY> type of the entity
     * @return the specification
     */
    private static <ENTITY extends BaseJpaEntity> Specification<ENTITY> notDeleted() {
        return (root, query, criteriaBuilder) -> criteriaBuilder.isNull(root.get(BaseEntity.DELETED_AT));
    }
}
