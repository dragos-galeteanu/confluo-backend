package com.confluo.notifications.dto.recipient;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request to add a recipient by hand.
 *
 * <p>The normal way a recipient comes to exist is an event from the service that owns users, and
 * this is the exception: someone fixing a row, or seeding one before that service exists. A
 * recipient created through here is recorded with {@code source = ADMIN}, which is not a field of
 * the request - provenance is something the service knows and a caller cannot claim.</p>
 *
 * <p>Both contact fields are optional and <strong>at least one is required</strong>, which is a
 * rule between two fields rather than about either, so it is checked in the service and answered
 * with a 422 rather than annotated here. The database refuses it too.</p>
 *
 * @param firebaseUid the Firebase uid this recipient is
 * @param email where email should go, lowercased on the way in
 * @param emailVerified whether the address has been proved to be theirs
 * @param phoneE164 the phone number in E.164, e.g. +40721234567
 * @param phoneVerified whether the number has been proved to be theirs
 * @param languageCode the language to render in, as the platform spells its codes
 * @param timezone the IANA zone, e.g. Europe/Bucharest
 */
public record RecipientCreateRequest(
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
