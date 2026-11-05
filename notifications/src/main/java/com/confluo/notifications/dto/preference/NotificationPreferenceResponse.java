package com.confluo.notifications.dto.preference;

import com.confluo.notifications.channel.Category;
import com.confluo.notifications.channel.Channel;

/**
 * What a recipient will and will not receive, as the API returns it.
 *
 * <p>Every combination of a channel and a category is returned, whether or not a row exists for it,
 * because the absence of a row is an answer rather than a gap - it means the default. {@code stored}
 * is what distinguishes the two, so a caller can tell "they chose this" from "nobody has said".</p>
 *
 * <p>Two of the channels have no sender yet. A preference for them is recorded and unused, which is
 * the honest thing to show: what someone has said does not depend on whether this service can act
 * on it today.</p>
 *
 * @param channel the channel
 * @param category the kind of message
 * @param enabled whether it will be sent
 * @param stored true if a recipient has an answer of their own, false if this is the default
 * @param declinable false for {@link Category#TRANSACTIONAL}, which cannot be switched off
 */
public record NotificationPreferenceResponse(
        Channel channel,
        Category category,
        boolean enabled,
        boolean stored,
        boolean declinable
) {
}
