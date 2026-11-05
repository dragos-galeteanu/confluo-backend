package com.confluo.notifications.repository.jpa;

import com.confluo.common.jpa.repository.BaseJpaRepository;
import com.confluo.notifications.channel.Channel;
import com.confluo.notifications.entity.Suppression;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Destinations nothing may be sent to again.
 *
 * <p>The existence check is on the hot path - every send consults it - and is deliberately an
 * {@code exists} rather than a find: the pipeline only needs to know whether to stop, and the
 * reason matters solely to whoever is reading the log afterwards.</p>
 *
 * <p>Both lookups are exact, and the caller lowercases the destination first. An {@code IgnoreCase}
 * method would compile to {@code upper(destination) = upper(?)} and could not use
 * {@code uq_suppressions_destination} - a table scan in front of every send - and worse, it would
 * disagree with the unique constraint, which is exact: two rows differing only in case would both
 * be allowed and only one of them found. {@code ck_suppressions_destination_lowercase} settles it
 * at the column instead.</p>
 */
@Repository
public interface SuppressionRepository extends BaseJpaRepository<Suppression> {

    /**
     * Whether a destination is suppressed on a channel.
     * @param channel the channel
     * @param destination the address, lowercased, or the E.164 number
     * @return true if nothing may be sent to it
     */
    boolean existsByChannelAndDestination(Channel channel, String destination);

    /**
     * The suppression itself, for the log and for lifting it.
     * @param channel the channel
     * @param destination the address, lowercased, or the E.164 number
     * @return the suppression, or empty if there is none
     */
    Optional<Suppression> findByChannelAndDestination(Channel channel, String destination);
}
