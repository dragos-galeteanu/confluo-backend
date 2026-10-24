package com.confluo.common.mapper;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Base mapper interface for converting between entities and request/response objects.
 * @param <ENTITY> type of the entity
 * @param <CREATE_REQUEST> type of the request object
 * @param <UPDATE_REQUEST> type of the request object
 * @param <RESPONSE> type of the response object
 */
public interface BaseMapper<ENTITY, CREATE_REQUEST, UPDATE_REQUEST, RESPONSE> {

    /**
     * Convert an entity to a response object.
     * @param entity the entity to convert
     * @return the response object
     */
    RESPONSE toResponse(ENTITY entity);

    /**
     * Convert a create request object to a newly created entity.
     * @param entity the entity to create
     * @param request the request object
     */
    void createEntityFromRequest(ENTITY entity, CREATE_REQUEST request);

    /**
     * Convert an update request object to an updated entity.
     * @param entity the entity to update
     * @param request the request object
     */
    void updateEntityFromRequest(ENTITY entity, UPDATE_REQUEST request);

    /**
     * Convert a list of entities to a list of response objects.
     * @param entityList the list of entities to convert
     * @return the list of response objects
     */
    default List<RESPONSE> toResponseList(List<ENTITY> entityList) {
        return entityList.stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }
}
