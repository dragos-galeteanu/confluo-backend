package com.confluo.notifications.dto.recipient;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Request to change a recipient.
 *
 * <p>The request carries the whole recipient, the uid included, so a response can be edited and
 * sent straight back. Unlike the platform's codes, the uid is <strong>not</strong> applied: it is
 * the identity this row stands for, and quietly re-pointing a row at a different person would move
 * their contact details, their preferences and their delivery history along with it. A uid that
 * differs from the stored one is refused with a 422 rather than ignored, because a field that is
 * accepted and discarded is worse than one that is rejected.</p>
 *
 * <p>An edit here sets {@code source = ADMIN}, whatever it was before. A row an event wrote and a
 * person then corrected is no longer the event's, and saying so is the whole reason provenance is
 * stored.</p>
 *
 * @param id the recipient to change
 * @param firebaseUid the uid, which must match the stored one
 * @param email where email should go, lowercased on the way in
 * @param emailVerified whether the address has been proved to be theirs
 * @param phoneE164 the phone number in E.164
 * @param phoneVerified whether the number has been proved to be theirs
 * @param languageCode the language to render in
 * @param timezone the IANA zone
 */
public record RecipientUpdateRequest(
        @NotNull
        UUID id,

        @NotBlank
        @Size(max = 128)
        String firebaseUid,

        @Email
        @Size(max = 320)
        String email,

        boolean emailVerified,

        @Pattern(regexp = "\\+[1-9][0-9]{1,14}", message = "Phone number must be in E.164, e.g. +40721234567")
        String phoneE164,

        boolean phoneVerified,

        @Pattern(regexp = "[A-Za-z]{2,8}(-[A-Za-z0-9]{2,8})?", message = "Language code must be an ISO 639 language tag")
        String languageCode,

        @Size(max = 64)
        String timezone
) {
}
