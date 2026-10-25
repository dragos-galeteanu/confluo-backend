package com.confluo.common.mongo.entity;

import lombok.Getter;
import lombok.Setter;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;

import java.time.Instant;

/**
 * Base class for documents stored in MongoDB.
 *
 * <p>The counterpart of {@code BaseJpaEntity}, declaring the same four lifecycle fields with Spring
 * Data's annotations, and spelling the soft-delete one as
 * {@link com.confluo.common.entity.BaseEntity#DELETED_AT} requires. It does not implement that
 * interface: the stack typed against it reaches a resource through a {@code UUID}-keyed
 * repository, which a document cannot satisfy, so implementing it would promise something this
 * class cannot deliver. The timestamps are filled by Spring Data's auditing, which is not on by
 * default: a service storing documents must put {@code @EnableMongoAuditing} on a configuration
 * class, or {@code createdAt} and {@code updatedAt} stay null.</p>
 *
 * <p>The identifier is an {@link ObjectId}, which is what MongoDB generates for a document that
 * arrives without one. Leaving identity to the store is what keeps this class a plain list of
 * fields: the id is null until the first save, so Spring Data can tell a new document from a loaded
 * one on its own, and auditing stamps the creation time without being told.</p>
 */
@Getter
@Setter
public class BaseMongoDocument {

    @Id
    protected ObjectId id;

    @CreatedDate
    protected Instant createdAt;

    @LastModifiedDate
    protected Instant updatedAt;

    protected Instant disabledAt = null;

    protected Instant deletedAt = null;
}
