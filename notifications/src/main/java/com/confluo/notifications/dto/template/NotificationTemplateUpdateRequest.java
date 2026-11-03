package com.confluo.notifications.dto.template;

import com.confluo.notifications.channel.Category;
import com.confluo.notifications.channel.Channel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Request to change the wording of a message.
 *
 * <p>The request carries the whole template so a response can be edited and sent straight back,
 * but the three fields that <em>identify</em> it - the key, the channel and the locale - are not
 * applied. They are what the compound unique index is on, so changing them would not be an edit at
 * all: it would be this wording quietly becoming a different message's, leaving the one it used to
 * be with nothing. A mismatch is refused with a 422. Moving wording to another locale is creating
 * it there and retiring it here.</p>
 *
 * <p>Every accepted edit bumps the template's revision, which is what each rendered message
 * records - so a message can always be traced to the wording that produced it.</p>
 *
 * @param id the template to change
 * @param templateKey the key, which must match the stored one
 * @param channel the channel, which must match the stored one
 * @param locale the locale, which must match the stored one
 * @param category what kind of message it is
 * @param subject the email subject; required for email, refused elsewhere
 * @param textBody the plain-text body, required on every channel
 * @param htmlBody the HTML body; email only
 * @param variables the variables this wording expects
 */
public record NotificationTemplateUpdateRequest(
        @NotBlank
        String id,

        @NotBlank
        String templateKey,

        @NotNull
        Channel channel,

        @NotBlank
        String locale,

        @NotNull
        Category category,

        @Size(max = 500)
        String subject,

        @NotBlank
        String textBody,

        String htmlBody,

        @NotNull
        List<@NotBlank String> variables
) {
}
