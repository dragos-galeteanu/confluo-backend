package com.confluo.common.jpa.entity;

import com.confluo.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Base class for entities stored in a relational database.
 *
 * <p>The fields are declared here rather than inherited from {@link BaseEntity}, which is an
 * interface: Hibernate only maps fields declared in a {@code @MappedSuperclass} or {@code @Entity}
 * type, so a field inherited from an ordinary class would be ignored.</p>
 */
@Getter
@Setter
@MappedSuperclass
public class BaseJpaEntity implements BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    protected UUID id;

    @CreationTimestamp
    @Column(nullable = false)
    protected Instant createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    protected Instant updatedAt;

    @Column
    protected Instant disabledAt = null;

    @Column
    protected Instant deletedAt = null;
}
