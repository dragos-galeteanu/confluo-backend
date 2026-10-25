package com.confluo.common.entity;

import java.time.Instant;

/**
 * The lifecycle of a resource the service and controller layers manage.
 *
 * <p>This exists so that {@link com.confluo.common.service.BaseService},
 * {@link com.confluo.common.controller.BaseController} and
 * {@link com.confluo.common.repository.BaseRepository} have something to be typed against from
 * this module, which cannot see the store-specific ones. It is an interface rather than a base
 * class because JPA only maps fields declared in an {@code @Entity} or {@code @MappedSuperclass}
 * type: fields inherited from an ordinary superclass would not be persisted at all. The relational
 * base, {@code com.confluo.common.jpa.entity.BaseJpaEntity}, declares them itself and implements
 * this.</p>
 *
 * <p>A MongoDB document carries the same four fields but does not implement this interface. It
 * could, and the compiler would accept it - but the promise would not be keepable: the stack above
 * reaches a resource through a {@code BaseRepository}, which is keyed by {@code UUID}, and a
 * document is keyed by an {@code ObjectId}. What the two stores do share is the naming below, and
 * {@link #DELETED_AT} is where that is written down rather than restated in prose.</p>
 */
public interface BaseEntity {

    /**
     * The name of the soft-delete field, as both stores spell it.
     *
     * <p>Each store's pagination mapper restricts a listing by writing a condition against this
     * name - a column in SQL, a document key in MongoDB - so the two must agree, and a base that
     * spells its field differently will not be filtered correctly.</p>
     */
    String DELETED_AT = "deletedAt";

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
