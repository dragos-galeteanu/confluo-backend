package com.confluo.common.service;

import com.confluo.common.dto.Paginated;
import com.confluo.common.entity.BaseEntity;
import com.confluo.common.filter.PaginationFilter;
import com.confluo.common.repository.BaseRepository;

import java.util.UUID;

/**
 * Base service interface for all services.
 * @param <ENTITY> type of the entity for the resource
 * @param <CREATE_REQUEST> type of the request object for creating a new resource
 * @param <UPDATE_REQUEST> type of the request object for updating an existing resource
 * @param <RESPONSE> type of the response object for the resource
 */
public interface BaseService<ENTITY extends BaseEntity, CREATE_REQUEST, UPDATE_REQUEST, RESPONSE> {

    /**
     * The repository this service reads and writes through.
     * @return the repository
     */
    BaseRepository<ENTITY> getRepository();

    /**
     * Creates a new resource.
     * @param request the request object containing the data for creating the resource
     * @return the response object containing the created resource
     */
    RESPONSE create(CREATE_REQUEST request);

    /**
     * Get entity by id.
     * @param id the id of the entity
     * @return the entity object
     */
    ENTITY getEntityById(UUID id);

    /**
     * Get a resource by its ID.
     * @param id the ID of the resource
     * @return the response object containing the resource
     */
    RESPONSE getById(UUID id);

    /**
     * Get all resources matching the filter, with pagination and sorting applied.
     * @param filter pagination, filter, and sort parameters
     * @return a paginated response object containing the list of resources
     */
    Paginated<RESPONSE> getAll(PaginationFilter filter);

    /**
     * Update an existing resource.
     * @param request the request object containing the data for updating the resource
     * @return the response object containing the updated resource
     */
    RESPONSE update(UPDATE_REQUEST request);

    /**
     * Disable an existing resource.
     * @param id the ID of the resource
     * @return the response object containing the disabled resource
     */
    RESPONSE disable(UUID id);

    /**
     * Delete an existing resource.
     * @param id the ID of the resource
     * @return the response object containing the deleted resource
     */
    RESPONSE delete(UUID id);
}
