package com.confluo.platform.dto.language;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request to add a language.
 * @param code the ISO 639-1 code, e.g. en
 * @param name the language's English name
 * @param nativeName the language's name in itself
 * @param isRightToLeft whether the script runs right to left
 */
public record LanguageCreateRequest(
        @NotBlank
        @Pattern(regexp = "[A-Za-z]{2,8}(-[A-Za-z0-9]{2,8})?", message = "Code must be an ISO 639 language tag")
        String code,

        @NotBlank
        @Size(max = 255)
        String name,

        @NotBlank
        @Size(max = 255)
        String nativeName,

        @NotNull
        @JsonProperty("isRightToLeft")
        Boolean isRightToLeft
) {
}
