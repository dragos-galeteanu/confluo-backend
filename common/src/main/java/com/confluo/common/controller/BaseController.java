package com.confluo.common.controller;

import com.confluo.common.dto.Paginated;
import com.confluo.common.entity.BaseEntity;
import com.confluo.common.filter.PaginationFilter;
import com.confluo.common.service.BaseService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

public abstract class BaseController<ENTITY extends BaseEntity, CREATE_REQUEST, UPDATE_REQUEST, RESPONSE> {

    /**
     * Get the service associated with this controller.
     * @return the service
     */
    protected abstract BaseService<ENTITY, CREATE_REQUEST, UPDATE_REQUEST, RESPONSE> getService();

    /**
     * Create a new resource.
     * @param request the request object containing the data for creating the resource
     * @return the response object containing the created resource
     */
    @PostMapping
    @Operation(summary = "Create a new resource")
    public ResponseEntity<RESPONSE> create(@Valid @RequestBody CREATE_REQUEST request) {
        RESPONSE response = getService().create(request);
        return new ResponseEntity<>(response, HttpStatus.CREATED);
    }

    /**
     * Get resource by ID.
     * @param id the ID of the resource
     * @return the response object containing the resource
     */
    @GetMapping("/{id}")
    @Operation(summary = "Get resource by ID")
    public ResponseEntity<RESPONSE> getById(@PathVariable UUID id) {
        RESPONSE response = getService().getById(id);
        return ResponseEntity.ok(response);
    }

    /**
     * Get all resources with pagination.
     * @param filter pagination filter
     * @return a paginated response object containing the list of resources
     */
    @GetMapping
    @Operation(summary = "Get all resources with pagination")
    public ResponseEntity<Paginated<RESPONSE>> getAll(@Valid @ModelAttribute PaginationFilter filter) {
        Paginated<RESPONSE> responses = getService().getAll(filter);
        return ResponseEntity.ok(responses);
    }

    /**
     * Update an existing resource.
     * @param request the request object containing the data for updating the resource
     * @return the response object containing the updated resource
     */
    @PutMapping
    @Operation(summary = "Update resource")
    public ResponseEntity<RESPONSE> update(@Valid @RequestBody UPDATE_REQUEST request) {
        RESPONSE response = getService().update(request);
        return ResponseEntity.ok(response);
    }

    /**
     * Disable an existing resource by ID.
     * @param id the ID of the resource
     * @return the response object containing the disabled resource
     */
    @DeleteMapping("/{id}/disable")
    @Operation(summary = "Disable an existing resource by ID")
    public ResponseEntity<RESPONSE> disable(@PathVariable UUID id) {
        RESPONSE response = getService().disable(id);
        return ResponseEntity.ok(response);
    }

    /**
     * Delete an existing resource by ID.
     * @param id the ID of the resource
     * @return the response object containing the deleted resource
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "Delete an existing resource by ID")
    public ResponseEntity<RESPONSE> delete(@PathVariable UUID id) {
        RESPONSE response = getService().delete(id);
        return ResponseEntity.ok(response);
    }
}
