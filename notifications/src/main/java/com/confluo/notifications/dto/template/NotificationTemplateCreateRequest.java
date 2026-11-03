package com.confluo.notifications.dto.template;

import com.confluo.notifications.channel.Category;
import com.confluo.notifications.channel.Channel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Request to add the wording of a message.
 *
 * <p>Three rules hold between fields rather than about any one of them, so they are checked in the
 * service and answered with a 422: an email needs a subject, a channel without subjects must not
 * carry one, and only email may have an HTML body.</p>
 *
 * @param templateKey what the message is, e.g. password-reset
 * @param channel the channel this wording is for
 * @param locale the language tag, lowercased on the way in
 * @param category what kind of message it is, and so whether a preference could stop it
 * @param subject the email subject; required for email, refused elsewhere
 * @param textBody the plain-text body, required on every channel
 * @param htmlBody the HTML body; email only, and optional there
 * @param variables the variables this wording expects, so a render missing one is refused
 */
public record NotificationTemplateCreateRequest(
        @NotBlank
        @Size(max = 128)
        @Pattern(regexp = "[a-z0-9]+(-[a-z0-9]+)*", message = "Template key must be lowercase words separated by hyphens")
        String templateKey,

        @NotNull
        Channel channel,

        @NotBlank
        @Pattern(regexp = "[A-Za-z]{2,8}([-_][A-Za-z0-9]{2,8})?", message = "Locale must be a language tag, e.g. ro or ro-RO")
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
