package com.confluo.platform.entity;

import com.confluo.common.jpa.entity.BaseJpaEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

/**
 * A currency the platform can price in, as defined by ISO 4217.
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
        name = "currencies",
        uniqueConstraints = @UniqueConstraint(name = "uq_currencies_code", columnNames = "code"))
public class Currency extends BaseJpaEntity {

    /** the ISO 4217 alphabetic code, e.g. EUR */
    @Column(name = "code", nullable = false, length = 3)
    private String code;

    /** the ISO 4217 numeric code, e.g. 978, kept as text so leading zeros survive */
    @Column(name = "numeric_code", nullable = false, length = 3)
    private String numericCode;

    /** the currency's English name */
    @Column(name = "name", nullable = false)
    private String name;

    /** the symbol shown with an amount, e.g. € */
    @Column(name = "symbol", nullable = false, length = 8)
    private String symbol;

    /** whether the symbol is written before or after the amount */
    @Enumerated(EnumType.STRING)
    @Column(name = "symbol_position", nullable = false, length = 8)
    private SymbolPosition symbolPosition;

    /**
     * How many decimal places an amount has: 2 for the euro, 0 for the yen, 3 for the dinar. Money
     * is stored in the minor unit, so this is what converts between the two.
     */
    @Column(name = "minor_units", nullable = false)
    private int minorUnits;
}
