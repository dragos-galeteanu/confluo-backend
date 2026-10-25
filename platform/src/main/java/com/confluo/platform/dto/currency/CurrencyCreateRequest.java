package com.confluo.platform.dto.currency;

import com.confluo.platform.entity.SymbolPosition;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request to add a currency.
 * @param code the ISO 4217 alphabetic code, e.g. EUR
 * @param numericCode the ISO 4217 numeric code, e.g. 978
 * @param name the currency's English name
 * @param symbol the symbol shown with an amount
 * @param symbolPosition whether the symbol precedes or follows the amount
 * @param minorUnits how many decimal places an amount has
 */
public record CurrencyCreateRequest(
        @NotBlank
        @Pattern(regexp = "[A-Za-z]{3}", message = "Code must be three letters")
        String code,

        @NotBlank
        @Pattern(regexp = "\\d{3}", message = "Numeric code must be three digits")
        String numericCode,

        @NotBlank
        @Size(max = 255)
        String name,

        @NotBlank
        @Size(max = 8)
        String symbol,

        @NotNull
        SymbolPosition symbolPosition,

        @NotNull
        @Min(value = 0, message = "Minor units cannot be negative")
        @Max(value = 8, message = "Minor units cannot exceed 8")
        Integer minorUnits
) {
}
