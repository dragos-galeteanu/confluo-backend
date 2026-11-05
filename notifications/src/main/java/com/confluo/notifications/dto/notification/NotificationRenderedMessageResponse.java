package com.confluo.notifications.dto.notification;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * The words a message was sent with, as the API returns them.
 *
 * <p>A response object rather than the document itself, because a {@code BaseMongoDocument} is
 * keyed by an {@code ObjectId} and Jackson renders one as a little structure of a timestamp, a
 * counter and a machine identifier. The document's own identifier is of no interest here anyway:
 * this content is only ever reached through the notification it belongs to.</p>
 *
 * @param notificationId the message these are the words of
 * @param subject the rendered subject, or null on a channel without one
 * @param textBody the rendered plain-text body
 * @param htmlBody the rendered HTML body, or null
 * @param variables what the template was filled in with
 * @param templateKey which template produced it
 * @param templateVersion which revision of that template
 * @param locale the locale actually rendered
 * @param renderedAt when the words were frozen
 */
public record NotificationRenderedMessageResponse(
        UUID notificationId,
        String subject,
        String textBody,
        String htmlBody,
        Map<String, Object> variables,
        String templateKey,
        int templateVersion,
        String locale,
        Instant renderedAt
) {
}
