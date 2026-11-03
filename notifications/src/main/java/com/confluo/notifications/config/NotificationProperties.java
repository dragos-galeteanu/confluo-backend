package com.confluo.notifications.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The settings that are this service's own rather than Spring's.
 *
 * <p>It holds only what something actually reads, and grows as the senders, the dispatcher and the
 * unsubscribe links arrive. A record of fields nothing consults yet would be a list of promises.</p>
 *
 * @param defaultLocale the language tag every locale chain ends at, so a message always has
 *                      wording to be sent in
 */
@Validated
@ConfigurationProperties(prefix = "confluo.notifications")
public record NotificationProperties(

        @NotBlank
        String defaultLocale
) {
}
