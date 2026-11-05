package com.confluo.common.mongo.repository;

import com.confluo.common.mongo.entity.BaseMongoDocument;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.repository.NoRepositoryBean;

/**
 * Base repository for documents stored in MongoDB.
 *
 * <p>Unlike the relational base this does not extend
 * {@link com.confluo.common.repository.BaseRepository}, which is keyed by {@code UUID} because that
 * is what the service and controller layers above it pass around. A document is keyed by an
 * {@link ObjectId}, so it stands outside that stack: a Mongo-backed resource that wants the shared
 * CRUD endpoints would need a UUID key of its own.</p>
 *
 * <p>Filtered listings do not go through the repository either: a {@code Criteria} query needs a
 * {@link org.springframework.data.mongodb.core.MongoTemplate}, which is what
 * {@link com.confluo.common.mongo.mapper.MongoPaginationMapper#findAll} uses.</p>
 *
 * <p>In a service that also talks to a relational database, keep these repositories in a package of
 * their own and name it in {@code @EnableMongoRepositories}, so Spring Data is told which module
 * owns them rather than having to work it out.</p>
 *
 * @param <ENTITY> type of the document for the resource
 */
@NoRepositoryBean
public interface BaseMongoRepository<ENTITY extends BaseMongoDocument>
        extends MongoRepository<ENTITY, ObjectId> {
}
