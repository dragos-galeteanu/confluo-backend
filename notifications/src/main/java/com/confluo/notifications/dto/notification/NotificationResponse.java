package com.confluo.notifications.dto.notification;

import com.confluo.notifications.channel.Category;
import com.confluo.notifications.channel.Channel;
import com.confluo.notifications.entity.NotificationStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * One message and where it has got to, as the API returns it.
 *
 * <p>The state and not the words. What a message said is in its rendered content, which is a
 * separate document and a separate route: a delivery log is read to answer "did this go, and when,
 * and why not", and putting kilobytes of HTML in every row of it would bury the answer.</p>
 *
 * <p>{@code destination} is in here. It is personal data, which is exactly why this route is
 * Admin and Support only - and leaving it out would make the log useless for the one question it is
 * most often opened for: where did we actually send that.</p>
 *
 * @param id the message's identifier
 * @param recipientId whose it is, or null for an ad-hoc send
 * @param firebaseUid the recipient's uid, or null for an ad-hoc send
 * @param channel how it goes
 * @param category what kind of message it is, as its template declared
 * @param templateKey which message it is
 * @param locale the locale actually rendered, after the fallback chain ran
 * @param destination the address or number it was addressed to, frozen at render time
 * @param status where it has got to
 * @param attempts how many provider calls have been made for it
 * @param nextAttemptAt when the dispatcher will next consider it
 * @param scheduledFor when it should not be sent before, or null
 * @param lastError what went wrong on the last attempt, or null
 * @param dedupKey what must not be sent twice, or null
 * @param correlationId what ties it to a wider conversation, or null
 * @param triggerEventId the event that asked for it, or null if an API call did
 * @param requestedBy who asked for it
 * @param createdAt when it was created
 * @param updatedAt when it was last changed
 * @param disabledAt when it was disabled
 * @param deletedAt when it was soft-deleted
 */
public record NotificationResponse(
        UUID id,
        UUID recipientId,
        String firebaseUid,
        Channel channel,
        Category category,
        String templateKey,
        String locale,
        String destination,
        NotificationStatus status,
        int attempts,
        Instant nextAttemptAt,
        Instant scheduledFor,
        String lastError,
        String dedupKey,
        String correlationId,
        UUID triggerEventId,
        String requestedBy,
        Instant createdAt,
        Instant updatedAt,
        Instant disabledAt,
        Instant deletedAt
) {
}
