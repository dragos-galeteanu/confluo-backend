package com.confluo.common.mongo.mapper;

import com.confluo.common.dto.Paginated;
import com.confluo.common.entity.BaseEntity;
import com.confluo.common.exception.InvalidFilterException;
import com.confluo.common.filter.PaginationFilter;
import com.confluo.common.mapper.PaginationMapper;
import com.confluo.common.mongo.entity.BaseMongoDocument;
import com.confluo.common.mongo.filter.FilterCriteriaBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import java.util.List;
import java.util.function.Function;

/**
 * Runs a listing against MongoDB: the filter expression as a {@link Criteria}, the paging and
 * sorting as a {@link Pageable}, and the resulting page mapped to response objects.
 *
 * <p>This takes {@link MongoOperations} rather than a repository, because a criteria query is not
 * something a derived repository method can express.</p>
 */
public class MongoPaginationMapper {

    /** logs the listings that were rejected, which the client only sees as a 400 */
    private static final Logger log = LoggerFactory.getLogger(MongoPaginationMapper.class);

    /**
     * Private constructor to prevent instantiation.
     */
    private MongoPaginationMapper() {}

    /**
     * Runs a listing. Soft-deleted documents are never returned; documents that are merely disabled
     * are, since disabling is a state the caller can filter on itself.
     *
     * <p>The count is taken with the same criteria but without paging, so the metadata describes the
     * whole result and not the page.</p>
     *
     * @param mongoOperations the template to read through
     * @param type the document type being listed
     * @param filter pagination, filter, and sort parameters
     * @param toResponse converts one document to its response object
     * @param <ENTITY> type of the document
     * @param <RESPONSE> type of the response object
     * @return the paginated response
     * @throws InvalidFilterException if the filter or sort expression is malformed
     */
    public static <ENTITY extends BaseMongoDocument, RESPONSE> Paginated<RESPONSE> findAll(
            MongoOperations mongoOperations, Class<ENTITY> type, PaginationFilter filter,
            Function<ENTITY, RESPONSE> toResponse) {
        Criteria criteria = notDeleted();
        Criteria requested = FilterCriteriaBuilder.build(filter.filter());
        Query query = new Query(requested == null ? criteria : new Criteria().andOperator(criteria, requested));

        Pageable pageable = PaginationMapper.toPageable(filter);
        try {
            long total = mongoOperations.count(Query.of(query).limit(-1).skip(-1), type);
            List<ENTITY> content = mongoOperations.find(query.with(pageable), type);
            Page<ENTITY> page = new PageImpl<>(content, pageable, total);
            return PaginationMapper.toPaginated(page, toResponse);
        } catch (InvalidDataAccessApiUsageException e) {
            log.warn("Rejected listing [filter={}, sort={}, reason={}]",
                    filter.filter(), filter.sort(), e.getMessage());
            throw new InvalidFilterException("Unknown field in the filter or sort expression");
        }
    }

    /**
     * Restricts a listing to the documents that have not been soft-deleted.
     * @return the criteria
     */
    private static Criteria notDeleted() {
        return Criteria.where(BaseEntity.DELETED_AT).is(null);
    }
}
