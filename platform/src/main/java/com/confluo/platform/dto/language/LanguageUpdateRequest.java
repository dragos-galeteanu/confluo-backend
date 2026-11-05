package com.confluo.platform.dto.language;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Request to change a language.
 *
 * <p>The request carries the whole language, the code included, so a response can be edited and
 * sent straight back. The code is applied, not ignored: a code another language already holds is
 * refused with a 422.</p>
 *
 * @param id the language to change
 * @param code the ISO 639-1 code, e.g. en
 * @param name the language's English name
 * @param nativeName the language's name in itself
 * @param isRightToLeft whether the script runs right to left
 */
public record LanguageUpdateRequest(
        @NotNull
        UUID id,

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
