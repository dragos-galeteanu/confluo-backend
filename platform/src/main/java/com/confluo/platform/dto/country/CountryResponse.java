package com.confluo.platform.dto.country;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A country as the API returns it.
 *
 * <p>The boolean fields are named without the {@code is} prefix in Java and with it on the wire.
 * That is deliberate: Lombok's getter for an entity field called {@code isEuMember} is
 * {@code isEuMember()}, which MapStruct reads as the property {@code isEuMember}, so a record
 * component called {@code isEuMember} would silently fail to map. The {@code @JsonProperty} keeps
 * the published contract the way it was specified.</p>
 *
 * <p>The default currency and language are ids, the same shape the create and update requests
 * take, so a response can be edited and sent back without reshaping it. A client that needs the
 * symbol or the language name reads the currency and language endpoints, which it will be holding
 * anyway to offer the choice.</p>
 *
 * @param id the country's identifier
 * @param alpha2 the ISO 3166-1 alpha-2 code
 * @param alpha3 the ISO 3166-1 alpha-3 code
 * @param numericCode the ISO 3166-1 numeric code
 * @param name the country's English name
 * @param callingCode the international dialling prefix, or null
 * @param standardVatRate the headline VAT rate as a percentage, or null
 * @param isEuVatMember whether the country is inside the EU VAT area
 * @param isEuMember whether the country is a member of the European Union
 * @param isEeaMember whether the country is part of the European Economic Area
 * @param isSchengenMember whether the country is in the Schengen area
 * @param defaultCurrencyId the currency prices default to, or null
 * @param defaultLanguageId the language the platform defaults to, or null
 * @param createdAt when the record was created
 * @param updatedAt when the record was last changed
 * @param disabledAt when the record was disabled
 * @param deletedAt when the record was soft-deleted
 */
public record CountryResponse(
        UUID id,
        String alpha2,
        String alpha3,
        String numericCode,
        String name,
        String callingCode,
        BigDecimal standardVatRate,

        @JsonProperty("isEuVatMember")
        boolean isEuVatMember,

        @JsonProperty("isEuMember")
        boolean isEuMember,

        @JsonProperty("isEeaMember")
        boolean isEeaMember,

        @JsonProperty("isSchengenMember")
        boolean isSchengenMember,

        UUID defaultCurrencyId,
        UUID defaultLanguageId,
        Instant createdAt,
        Instant updatedAt,
        Instant disabledAt,
        Instant deletedAt
) {
}
