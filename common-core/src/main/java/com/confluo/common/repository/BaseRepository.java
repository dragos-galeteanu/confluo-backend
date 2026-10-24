package com.confluo.common.repository;

import com.confluo.common.entity.BaseEntity;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.ListPagingAndSortingRepository;
import org.springframework.data.repository.NoRepositoryBean;

import java.util.UUID;

/**
 * The repository contract shared by every store.
 *
 * <p>Only the Spring Data interfaces that both JPA and MongoDB implement appear here, which is what
 * lets {@link com.confluo.common.service.BaseService} hold a repository without knowing where its
 * resources live. A store's own base - {@code BaseJpaRepository} or {@code BaseMongoRepository} -
 * adds the querying that store supports.</p>
 *
 * <p>The identifier is always a {@link UUID}, as {@link BaseEntity} declares.</p>
 *
 * @param <ENTITY> type of the entity for the resource
 */
@NoRepositoryBean
public interface BaseRepository<ENTITY extends BaseEntity>
        extends ListCrudRepository<ENTITY, UUID>, ListPagingAndSortingRepository<ENTITY, UUID> {
}
