package com.confluo.platform.dto.language;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.UUID;

/**
 * A language as the API returns it.
 * @param id the language's identifier
 * @param code the ISO 639-1 code
 * @param name the language's English name
 * @param nativeName the language's name in itself
 * @param isRightToLeft whether the script runs right to left
 * @param createdAt when the record was created
 * @param updatedAt when the record was last changed
 * @param disabledAt when the record was disabled
 * @param deletedAt when the record was soft-deleted
 */
public record LanguageResponse(
        UUID id,
        String code,
        String name,
        String nativeName,

        @JsonProperty("isRightToLeft")
        boolean isRightToLeft,

        Instant createdAt,
        Instant updatedAt,
        Instant disabledAt,
        Instant deletedAt
) {
}
