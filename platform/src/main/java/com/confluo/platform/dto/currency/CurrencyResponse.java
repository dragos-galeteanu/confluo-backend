package com.confluo.platform.dto.currency;

import com.confluo.platform.entity.SymbolPosition;

import java.time.Instant;
import java.util.UUID;

/**
 * A currency as the API returns it.
 * @param id the currency's identifier
 * @param code the ISO 4217 alphabetic code
 * @param numericCode the ISO 4217 numeric code
 * @param name the currency's English name
 * @param symbol the symbol shown with an amount
 * @param symbolPosition whether the symbol precedes or follows the amount
 * @param minorUnits how many decimal places an amount has
 * @param createdAt when the record was created
 * @param updatedAt when the record was last changed
 * @param disabledAt when the record was disabled
 * @param deletedAt when the record was soft-deleted
 */
public record CurrencyResponse(
        UUID id,
        String code,
        String numericCode,
        String name,
        String symbol,
        SymbolPosition symbolPosition,
        int minorUnits,
        Instant createdAt,
        Instant updatedAt,
        Instant disabledAt,
        Instant deletedAt
) {
}
