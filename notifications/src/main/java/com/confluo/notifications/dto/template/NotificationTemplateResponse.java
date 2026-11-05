package com.confluo.notifications.dto.template;

import com.confluo.notifications.channel.Category;
import com.confluo.notifications.channel.Channel;

import java.time.Instant;
import java.util.List;

/**
 * The wording of a message, as the API returns it.
 *
 * <p>The identifier is the {@code ObjectId}'s hex string rather than the object itself. Left as an
 * {@code ObjectId}, Jackson renders it as a little structure of a timestamp, a counter and a
 * machine identifier, which is neither what anyone wants to read nor what they can send back.</p>
 *
 * @param id the template's identifier, as a 24-character hex string
 * @param templateKey what the message is
 * @param channel the channel this wording is for
 * @param locale the language tag
 * @param category what kind of message it is
 * @param subject the email subject, or null on a channel without one
 * @param textBody the plain-text body
 * @param htmlBody the HTML body, or null
 * @param variables the variables this wording expects
 * @param version the revision, bumped on each edit
 * @param createdAt when the record was created
 * @param updatedAt when the record was last changed
 * @param disabledAt when the template was retired; null while it is live
 * @param deletedAt when the record was soft-deleted
 */
public record NotificationTemplateResponse(
        String id,
        String templateKey,
        Channel channel,
        String locale,
        Category category,
        String subject,
        String textBody,
        String htmlBody,
        List<String> variables,
        int version,
        Instant createdAt,
        Instant updatedAt,
        Instant disabledAt,
        Instant deletedAt
) {
}
