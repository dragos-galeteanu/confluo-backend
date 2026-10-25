package com.confluo.platform.dto.country;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Request to add a country.
 *
 * <p>The default currency and language are given by id, as returned by the currency and language
 * endpoints. An id that matches nothing is reported as a 422 rather than a 404: the country in the
 * request is not the thing that is missing.</p>
 *
 * @param alpha2 the ISO 3166-1 alpha-2 code
 * @param alpha3 the ISO 3166-1 alpha-3 code
 * @param numericCode the ISO 3166-1 numeric code
 * @param name the country's English name
 * @param callingCode the international dialling prefix, nullable
 * @param standardVatRate the headline VAT rate as a percentage, nullable
 * @param isEuVatMember whether the country is inside the EU VAT area
 * @param isEuMember whether the country is a member of the European Union
 * @param isEeaMember whether the country is part of the European Economic Area
 * @param isSchengenMember whether the country is in the Schengen area
 * @param defaultCurrencyId the currency prices default to, nullable
 * @param defaultLanguageId the language the platform defaults to, nullable
 */
public record CountryCreateRequest(
        @NotBlank
        @Pattern(regexp = "[A-Za-z]{2}", message = "Alpha-2 code must be two letters")
        String alpha2,

        @NotBlank
        @Pattern(regexp = "[A-Za-z]{3}", message = "Alpha-3 code must be three letters")
        String alpha3,

        @NotBlank
        @Pattern(regexp = "\\d{3}", message = "Numeric code must be three digits")
        String numericCode,

        @NotBlank
        @Size(max = 255)
        String name,

        @Pattern(regexp = "\\+\\d{1,6}", message = "Calling code must look like +49")
        String callingCode,

        @DecimalMin(value = "0.00", message = "VAT rate cannot be negative")
        @DecimalMax(value = "100.00", message = "VAT rate cannot exceed 100")
        BigDecimal standardVatRate,

        @NotNull
        @JsonProperty("isEuVatMember")
        Boolean isEuVatMember,

        @NotNull
        @JsonProperty("isEuMember")
        Boolean isEuMember,

        @NotNull
        @JsonProperty("isEeaMember")
        Boolean isEeaMember,

        @NotNull
        @JsonProperty("isSchengenMember")
        Boolean isSchengenMember,

        UUID defaultCurrencyId,

        UUID defaultLanguageId
) {
}
