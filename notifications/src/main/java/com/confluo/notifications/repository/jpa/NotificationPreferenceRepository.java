package com.confluo.notifications.repository.jpa;

import com.confluo.common.jpa.repository.BaseJpaRepository;
import com.confluo.notifications.channel.Category;
import com.confluo.notifications.channel.Channel;
import com.confluo.notifications.entity.NotificationPreference;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What each recipient has said about each kind of message on each channel.
 *
 * <p>Most combinations have no row, which means the default rather than nothing - so the pipeline
 * reads the one it is about and falls back, and the admin API reads the whole set for a person and
 * fills the gaps in for display.</p>
 */
@Repository
public interface NotificationPreferenceRepository extends BaseJpaRepository<NotificationPreference> {

    /**
     * Everything one recipient has said.
     * @param recipientId the recipient
     * @return their preferences, which may be empty
     */
    List<NotificationPreference> findByRecipientId(UUID recipientId);

    /**
     * The one preference a send has to consult.
     * @param recipientId the recipient
     * @param channel the channel
     * @param category the kind of message
     * @return the preference, or empty when the default applies
     */
    Optional<NotificationPreference> findByRecipientIdAndChannelAndCategory(
            UUID recipientId, Channel channel, Category category);
}
