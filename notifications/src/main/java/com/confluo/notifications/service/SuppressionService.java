package com.confluo.notifications.service;

import com.confluo.notifications.channel.Channel;
import com.confluo.notifications.entity.Suppression;
import com.confluo.notifications.entity.SuppressionReason;
import com.confluo.notifications.repository.jpa.SuppressionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

/**
 * The destinations nothing may be sent to again.
 *
 * <p>Every send consults this, so the check is an {@code exists} on the exact
 * {@code (channel, destination)} the unique constraint is on - which is also why
 * {@link #normalize} exists and is not optional. The column is constrained to lowercase, so a
 * destination that reaches the database in any other spelling would be refused on write and, worse,
 * missed on read.</p>
 *
 * <p>Keyed by the destination and not by a person, deliberately: an address that hard-bounced or a
 * number that answered STOP has to stay suppressed even when it is later attached to a different
 * uid. That is the failure which turns into a spam complaint.</p>
 */
@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class SuppressionService {

    private final SuppressionRepository repository;

    /**
     * Whether nothing may be sent to a destination on a channel.
     * @param channel the channel
     * @param destination the address or number, in any spelling
     * @return true if it is suppressed
     */
    @Transactional(readOnly = true)
    public boolean isSuppressed(Channel channel, String destination) {
        return repository.existsByChannelAndDestination(channel, normalize(destination));
    }

    /**
     * The suppression on a destination, for the log and for explaining a message nobody got.
     * @param channel the channel
     * @param destination the address or number
     * @return the suppression, or empty if there is none
     */
    @Transactional(readOnly = true)
    public Optional<Suppression> find(Channel channel, String destination) {
        return repository.findByChannelAndDestination(channel, normalize(destination));
    }

    /**
     * Stops anything further being sent to a destination.
     *
     * <p>Idempotent, and the first reason wins. A number that answered STOP and later also failed
     * to deliver is still, most usefully, a number whose owner asked to be left alone - and
     * overwriting that with {@code UNDELIVERED} would lose the only reason a person cannot
     * reverse.</p>
     *
     * @param channel the channel
     * @param destination the address or number
     * @param reason why
     * @param suppressedAt when it became true, which is not always now - a bounce notification can
     *                     arrive hours after the bounce
     * @param notes anything a person finding this row should know, or null
     * @return the suppression, existing or newly created
     */
    public Suppression suppress(Channel channel, String destination, SuppressionReason reason,
                                Instant suppressedAt, String notes) {
        String normalized = normalize(destination);
        return repository.findByChannelAndDestination(channel, normalized)
                .orElseGet(() -> {
                    Suppression suppression = new Suppression();
                    suppression.setChannel(channel);
                    suppression.setDestination(normalized);
                    suppression.setReason(reason);
                    suppression.setSuppressedAt(suppressedAt);
                    suppression.setNotes(notes);

                    Suppression saved = repository.save(suppression);
                    log.info("Destination suppressed [channel={}, reason={}, id={}]",
                            channel, reason, saved.getId());
                    return saved;
                });
    }

    /**
     * The stored form of a destination.
     *
     * <p>Lowercased, which {@code ck_suppressions_destination_lowercase} requires. An address is
     * treated case-insensitively by every mail provider that exists, and a phone number in E.164
     * has no case to lose.</p>
     *
     * @param destination the address or number as it arrived
     * @return it lowercased
     */
    public static String normalize(String destination) {
        return destination == null ? null : destination.toLowerCase(Locale.ROOT);
    }
}
