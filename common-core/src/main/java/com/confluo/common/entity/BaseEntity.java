package com.confluo.common.entity;

import java.time.Instant;
import java.util.UUID;

/**
 * What every stored resource has, whatever it is stored in.
 *
 * <p>This is an interface rather than a base class because the two stores cannot share one. JPA
 * only maps fields declared in an {@code @Entity} or {@code @MappedSuperclass} type, so fields
 * inherited from an ordinary superclass would simply not be persisted. Each store therefore brings
 * its own base - {@code com.confluo.common.jpa.entity.BaseJpaEntity} and
 * {@code com.confluo.common.mongo.entity.BaseMongoDocument} - declaring the fields with its own
 * mapping annotations, and the abstractions above them work against this interface.</p>
 *
 * <p>Both bases name the fields exactly as the accessors below do. The pagination machinery
 * restricts listings with a {@code deletedAt IS NULL} condition written against that name, so an
 * implementation that spells it differently will not be filtered correctly.</p>
 */
public interface BaseEntity {

    /**
     * The resource's identifier, assigned when it is first stored.
     * @return the id, or null before the resource has been stored
     */
    UUID getId();

    /**
     * When the resource was created.
     * @return the creation timestamp
     */
    Instant getCreatedAt();

    /**
     * When the resource was last written.
     * @return the update timestamp
     */
    Instant getUpdatedAt();

    /**
     * When the resource was disabled, if it was. A disabled resource still exists and is still
     * listed; what being disabled means is the owning service's business.
     * @return the timestamp, or null if the resource is active
     */
    Instant getDisabledAt();

    /**
     * When the resource was soft-deleted, if it was. A deleted resource is excluded from every
     * listing.
     * @return the timestamp, or null if the resource has not been deleted
     */
    Instant getDeletedAt();
}
