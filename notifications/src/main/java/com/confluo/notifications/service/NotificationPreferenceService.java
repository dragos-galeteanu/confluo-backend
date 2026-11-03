package com.confluo.notifications.service;

import com.confluo.notifications.channel.Category;
import com.confluo.notifications.channel.Channel;
import com.confluo.notifications.dto.preference.NotificationPreferenceResponse;
import com.confluo.notifications.dto.preference.NotificationPreferenceSetting;
import com.confluo.notifications.dto.preference.NotificationPreferencesUpdateRequest;
import com.confluo.notifications.entity.NotificationPreference;
import com.confluo.notifications.entity.Recipient;
import com.confluo.notifications.repository.jpa.NotificationPreferenceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What each recipient has agreed to receive.
 *
 * <p>Most combinations of a recipient, a channel and a category have no row, and the default
 * applies - {@link Category#enabledByDefault()} decides it. That is why reading a preference goes
 * through {@link #isAllowed} rather than through the repository directly: a caller that looks for a
 * row and finds none has learned nothing, and the two places this is asked from must not disagree.
 * </p>
 *
 * <p>{@link Category#TRANSACTIONAL} cannot be switched off, and that is refused in three places
 * on purpose: here, so the caller gets a 422 saying why; by the enum, so the rule is written next
 * to the thing it is about; and by {@code ck_notification_preferences_transactional}, so no future
 * code path can get around either. The duplication is the point - a hole here is invisible,
 * because nobody complains about the message they did not get.</p>
 */
@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class NotificationPreferenceService {

    private final NotificationPreferenceRepository repository;
    private final RecipientService recipients;

    /**
     * Whether a message of this kind may go to this recipient on this channel.
     *
     * <p>The one question the pipeline asks. A disabled recipient is refused everything declinable,
     * because disabling someone is how "stop sending to them" is said - but a transactional message
     * still goes, since it is part of an action they are in the middle of and a password reset that
     * silently vanishes is worse than one sent to someone marked inactive.</p>
     *
     * @param recipient whom it is for
     * @param channel how it would go
     * @param category what kind of message it is
     * @return true if it may be sent
     */
    @Transactional(readOnly = true)
    public boolean isAllowed(Recipient recipient, Channel channel, Category category) {
        if (!category.declinable()) {
            return true;
        }
        if (recipient.getDisabledAt() != null || recipient.getDeletedAt() != null) {
            log.debug("Preference denied: recipient is not active [uid={}, channel={}, category={}]",
                    recipient.getFirebaseUid(), channel, category);
            return false;
        }
        return repository.findByRecipientIdAndChannelAndCategory(recipient.getId(), channel, category)
                .map(NotificationPreference::isEnabled)
                .orElseGet(category::enabledByDefault);
    }

    /**
     * Everything a recipient will and will not receive, with the gaps filled in.
     *
     * <p>Every channel and category is returned, including the two channels that have no sender
     * yet: what someone has said does not depend on whether this service can act on it today.</p>
     *
     * @param firebaseUid the recipient
     * @return one entry per channel and category, never empty
     */
    @Transactional(readOnly = true)
    public List<NotificationPreferenceResponse> listFor(String firebaseUid) {
        Recipient recipient = recipients.getEntityByFirebaseUid(firebaseUid);
        Map<Channel, Map<Category, NotificationPreference>> stored = index(recipient.getId());

        List<NotificationPreferenceResponse> effective = new ArrayList<>();
        for (Channel channel : Channel.values()) {
            for (Category category : Category.values()) {
                NotificationPreference row = stored
                        .getOrDefault(channel, Map.of())
                        .get(category);
                effective.add(new NotificationPreferenceResponse(
                        channel,
                        category,
                        row == null ? category.enabledByDefault() : row.isEnabled(),
                        row != null,
                        category.declinable()));
            }
        }
        return effective;
    }

    /**
     * Applies some of a recipient's answers, leaving the rest alone.
     *
     * <p>All of them or none: one transaction, and a refusal on any setting leaves the others
     * unwritten. A caller changing three answers at once should not have to work out which two
     * landed.</p>
     *
     * @param firebaseUid the recipient
     * @param request the settings to apply
     * @return the recipient's whole set afterwards, as {@link #listFor} returns it
     */
    public List<NotificationPreferenceResponse> apply(String firebaseUid, NotificationPreferencesUpdateRequest request) {
        Recipient recipient = recipients.getEntityByFirebaseUid(firebaseUid);

        for (NotificationPreferenceSetting setting : request.preferences()) {
            if (!setting.enabled() && !setting.category().declinable()) {
                throw new IllegalStateException(
                        "%s messages cannot be switched off".formatted(setting.category()));
            }
            upsert(recipient, setting);
        }

        log.info("Preferences applied [uid={}, count={}]", firebaseUid, request.preferences().size());
        return listFor(firebaseUid);
    }

    private void upsert(Recipient recipient, NotificationPreferenceSetting setting) {
        NotificationPreference preference = repository
                .findByRecipientIdAndChannelAndCategory(recipient.getId(), setting.channel(), setting.category())
                .orElseGet(() -> {
                    NotificationPreference fresh = new NotificationPreference();
                    fresh.setRecipient(recipient);
                    fresh.setChannel(setting.channel());
                    fresh.setCategory(setting.category());
                    return fresh;
                });
        preference.setEnabled(setting.enabled());
        repository.save(preference);
    }

    /**
     * A recipient's stored rows, arranged so the listing can look each combination up once.
     * @param recipientId the recipient
     * @return channel to category to row
     */
    private Map<Channel, Map<Category, NotificationPreference>> index(UUID recipientId) {
        Map<Channel, Map<Category, NotificationPreference>> byChannel = new EnumMap<>(Channel.class);
        for (NotificationPreference preference : repository.findByRecipientId(recipientId)) {
            byChannel
                    .computeIfAbsent(preference.getChannel(), channel -> new EnumMap<>(Category.class))
                    .put(preference.getCategory(), preference);
        }
        return byChannel;
    }
}
