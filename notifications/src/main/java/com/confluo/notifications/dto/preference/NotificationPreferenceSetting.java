package com.confluo.notifications.dto.preference;

import com.confluo.notifications.channel.Category;
import com.confluo.notifications.channel.Channel;
import jakarta.validation.constraints.NotNull;

/**
 * One answer being set: a channel, a kind of message, and whether it is wanted.
 *
 * @param channel the channel
 * @param category the kind of message
 * @param enabled whether they want it; false is refused for {@link Category#TRANSACTIONAL}
 */
public record NotificationPreferenceSetting(
        @NotNull
        Channel channel,

        @NotNull
        Category category,

        boolean enabled
) {
}
