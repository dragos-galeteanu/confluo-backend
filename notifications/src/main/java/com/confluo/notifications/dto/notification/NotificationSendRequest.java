package com.confluo.notifications.dto.notification;

import com.confluo.notifications.channel.Channel;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * Request to send one message.
 *
 * <p>Two shapes, and exactly one of them. <strong>Named</strong>: {@link #firebaseUid} says who it
 * is for, and the address or number is this service's to know - optionally helped by the contact
 * fields, for a uid it has never heard of. <strong>Ad-hoc</strong>: {@link #destination} says
 * where to send and names nobody. Which one was meant is not guessable from a half-filled request,
 * so sending both or neither is refused.</p>
 *
 * <p>An ad-hoc send <strong>cannot consult preferences</strong>, because there is no person whose
 * preferences they would be. It still consults the suppression list, so an unsubscribe or a STOP is
 * honoured either way - but someone who has merely switched a category off is not protected by it.
 * That is why the route is an administrator's, and why the normal path is the named one.</p>
 *
 * <p>No category here, and that is deliberate: the template declares it. A caller who could name
 * the category could call their marketing transactional and make it undeclinable.</p>
 *
 * @param channel how to send it
 * @param templateKey which message to send
 * @param firebaseUid whom it is for; required unless {@link #destination} is given
 * @param email a contact detail for a uid this service does not know yet
 * @param phoneE164 a contact detail for a uid this service does not know yet
 * @param languageCode a language for a uid this service does not know yet
 * @param destination send straight here and record no recipient; required unless
 *                    {@link #firebaseUid} is given
 * @param locale render in this language instead of the recipient's
 * @param variables what to fill the template in with
 * @param dedupKey what must not be sent twice, ever; null for a repeatable send
 * @param correlationId what ties this message to a wider conversation
 */
public record NotificationSendRequest(
        @NotNull
        Channel channel,

        @NotBlank
        @Size(max = 128)
        String templateKey,

        @Size(max = 128)
        String firebaseUid,

        @Email
        @Size(max = 320)
        String email,

        @Pattern(regexp = "\\+[1-9][0-9]{1,14}", message = "Phone number must be in E.164, e.g. +40721234567")
        String phoneE164,

        @Pattern(regexp = "[A-Za-z]{2,8}([-_][A-Za-z0-9]{2,8})?", message = "Language code must be a language tag")
        String languageCode,

        @Size(max = 320)
        String destination,

        @Pattern(regexp = "[A-Za-z]{2,8}([-_][A-Za-z0-9]{2,8})?", message = "Locale must be a language tag")
        String locale,

        Map<String, Object> variables,

        @Size(max = 255)
        String dedupKey,

        @Size(max = 128)
        String correlationId
) {
}
