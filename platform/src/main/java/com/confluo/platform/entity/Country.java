package com.confluo.platform.entity;

import com.confluo.common.jpa.entity.BaseJpaEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * A country the platform operates in, as defined by ISO 3166-1, together with the tax and trade-bloc
 * facts the rest of the platform needs in order to price and ship correctly.
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
        name = "countries",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_countries_alpha2", columnNames = "alpha2"),
                @UniqueConstraint(name = "uq_countries_alpha3", columnNames = "alpha3"),
                @UniqueConstraint(name = "uq_countries_numeric_code", columnNames = "numeric_code")
        })
public class Country extends BaseJpaEntity {

    /** the ISO 3166-1 alpha-2 code, e.g. DE */
    @Column(name = "alpha2", nullable = false, length = 2)
    private String alpha2;

    /** the ISO 3166-1 alpha-3 code, e.g. DEU */
    @Column(name = "alpha3", nullable = false, length = 3)
    private String alpha3;

    /** the ISO 3166-1 numeric code, e.g. 276, kept as text so leading zeros survive */
    @Column(name = "numeric_code", nullable = false, length = 3)
    private String numericCode;

    /** the country's English name */
    @Column(name = "name", nullable = false)
    private String name;

    /** the international dialling prefix, e.g. +49; null where the country has none of its own */
    @Column(name = "calling_code", length = 8)
    private String callingCode;

    /** the headline VAT rate as a percentage, e.g. 19.00; null where VAT does not apply */
    @Column(name = "standard_vat_rate", precision = 5, scale = 2)
    private BigDecimal standardVatRate;

    /** whether the country is inside the EU VAT area, which is not the same as EU membership */
    @Column(name = "is_eu_vat_member", nullable = false)
    private boolean isEuVatMember;

    /** whether the country is a member of the European Union */
    @Column(name = "is_eu_member", nullable = false)
    private boolean isEuMember;

    /** whether the country is part of the European Economic Area */
    @Column(name = "is_eea_member", nullable = false)
    private boolean isEeaMember;

    /** whether the country is in the Schengen area */
    @Column(name = "is_schengen_member", nullable = false)
    private boolean isSchengenMember;

    /** the currency prices default to in this country; null where no single currency applies */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "default_currency_id")
    private Currency defaultCurrency;

    /** the language the platform defaults to in this country; null where none is obvious */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "default_language_id")
    private Language defaultLanguage;
}
