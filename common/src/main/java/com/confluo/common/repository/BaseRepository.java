package com.confluo.common.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Base repository interface for all repositories.
 *
 * <p>{@link JpaSpecificationExecutor} is required by
 * {@link com.jobbiegiggle.common.mapper.PaginationMapper#findAll}, which runs the filter expression
 * as a {@link org.springframework.data.jpa.domain.Specification}.</p>
 *
 * @param <ENTITY> type of the entity for the resource
 */
public interface BaseRepository<ENTITY> extends JpaRepository<ENTITY, JpaSpecificationExecutor<ENTITY>> {
}
