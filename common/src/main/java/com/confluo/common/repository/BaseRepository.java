package com.confluo.common.repository;

import com.confluo.common.entity.BaseEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.NoRepositoryBean;

import java.util.UUID;

/**
 * Base repository interface for all repositories.
 *
 * <p>{@link JpaSpecificationExecutor} is required by
 * {@link com.confluo.common.mapper.PaginationMapper#findAll}, which runs the filter expression
 * as a {@link org.springframework.data.jpa.domain.Specification}.</p>
 *
 * <p>The identifier is always a {@link UUID}, as declared by {@link BaseEntity}; Spring Data checks
 * the declared type against the entity's own {@code @Id} when it builds each repository proxy.</p>
 *
 * @param <ENTITY> type of the entity for the resource
 */
@NoRepositoryBean
public interface BaseRepository<ENTITY extends BaseEntity>
        extends JpaRepository<ENTITY, UUID>, JpaSpecificationExecutor<ENTITY> {
}
