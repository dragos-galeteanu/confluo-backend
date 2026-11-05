package com.confluo.platform.entity;

import com.confluo.common.jpa.entity.BaseJpaEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

/**
 * A language the platform can be read in, as defined by ISO 639-1.
 *
 * <p>The unique constraints are named to match the ones
 * {@code V1__create_reference_tables.sql} creates, so the entity and the migration can be read
 * against each other. Declaring them here does not create them - the schema is Flyway's and
 * Hibernate runs with {@code ddl-auto: validate} - but it keeps the entity honest about the shape
 * of the table it is mapped to.</p>
 */
@Getter
@Setter
@Entity
@Table(
        name = "languages",
        uniqueConstraints = @UniqueConstraint(name = "uq_languages_code", columnNames = "code"))
public class Language extends BaseJpaEntity {

    /** the ISO 639-1 two-letter code, e.g. en */
    @Column(name = "code", nullable = false, length = 8)
    private String code;

    /** the language's English name, e.g. German */
    @Column(name = "name", nullable = false)
    private String name;

    /** the language's name in itself, e.g. Deutsch */
    @Column(name = "native_name", nullable = false)
    private String nativeName;

    /** whether the script runs right to left, which the clients need in order to lay a page out */
    @Column(name = "is_right_to_left", nullable = false)
    private boolean isRightToLeft;
}
