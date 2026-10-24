package com.confluo.common.jpa.repository;

import com.confluo.common.jpa.entity.BaseJpaEntity;
import com.confluo.common.repository.BaseRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.NoRepositoryBean;

import java.util.UUID;

/**
 * Base repository for entities stored in a relational database.
 *
 * <p>{@link JpaSpecificationExecutor} is required by
 * {@link com.confluo.common.jpa.mapper.JpaPaginationMapper#findAll}, which runs the filter
 * expression as a {@link org.springframework.data.jpa.domain.Specification}.</p>
 *
 * <p>In a service that also talks to MongoDB, keep these repositories in a package of their own and
 * name it in {@code @EnableJpaRepositories}: Spring Data has to decide which module owns each
 * repository interface, and being explicit turns a confusing startup failure into a setting.</p>
 *
 * @param <ENTITY> type of the entity for the resource
 */
@NoRepositoryBean
public interface BaseJpaRepository<ENTITY extends BaseJpaEntity>
        extends BaseRepository<ENTITY>, JpaRepository<ENTITY, UUID>, JpaSpecificationExecutor<ENTITY> {
}
