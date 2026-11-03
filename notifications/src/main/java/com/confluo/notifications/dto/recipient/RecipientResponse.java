package com.confluo.notifications.dto.recipient;

import com.confluo.notifications.entity.RecipientSource;

import java.time.Instant;
import java.util.UUID;

/**
 * A recipient as the API returns it.
 *
 * <p>{@code source} and {@code lastSyncedAt} are here rather than hidden as internal bookkeeping,
 * because they are what someone looking at a wrong address actually needs: where it came from and
 * how old it is.</p>
 *
 * @param id the recipient's identifier
 * @param firebaseUid the Firebase uid this recipient is
 * @param email where email goes
 * @param emailVerified whether the address has been proved to be theirs
 * @param phoneE164 the phone number in E.164
 * @param phoneVerified whether the number has been proved to be theirs
 * @param languageCode the language to render in
 * @param timezone the IANA zone
 * @param source how this row came to say what it says
 * @param lastSyncedAt when the contact details were last written
 * @param createdAt when the record was created
 * @param updatedAt when the record was last changed
 * @param disabledAt when the record was disabled
 * @param deletedAt when the record was soft-deleted
 */
public record RecipientResponse(
        UUID id,
        String firebaseUid,
        String email,
        boolean emailVerified,
        String phoneE164,
        boolean phoneVerified,
        String languageCode,
        String timezone,
        RecipientSource source,
        Instant lastSyncedAt,
        Instant createdAt,
        Instant updatedAt,
        Instant disabledAt,
        Instant deletedAt
) {
}
