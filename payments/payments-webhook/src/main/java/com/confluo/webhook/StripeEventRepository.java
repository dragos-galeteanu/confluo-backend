package com.confluo.webhook;

import com.confluo.common.jpa.repository.BaseJpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;


/**
 * The webhook ledger.
 *
 * <p>What the sweep runs is <strong>not</strong> here. Claiming unapplied rows needs
 * {@code FOR UPDATE SKIP LOCKED} so that two instances cannot apply one event between them, and
 * that query lives in its own component next to the sweep rather than as a derived method
 * pretending to be ordinary - the same separation the notifications service makes for its
 * dispatcher, and common-amqp for its outbox drainer. What is here is what the webhook endpoint and
 * the rest of the service ask.</p>
 */
@Repository
public interface StripeEventRepository extends BaseJpaRepository<StripeEvent> {

    /**
     * The ledger row for a Stripe event.
     *
     * <p>The unique constraint is what actually decides - this is the cheap look before the insert,
     * so a redelivery costs a query rather than a caught constraint violation. It returns the row
     * rather than a boolean because a redelivered event deserves to be answered with what is
     * already known about it: whether it was applied, ignored, or failed and is waiting to be
     * retried.</p>
     *
     * @param stripeEventId Stripe's {@code evt_} identifier
     * @return the row, or empty if this is genuinely the first delivery
     */
    Optional<StripeEvent> findByStripeEventId(String stripeEventId);

    /**
     * Whether an event has been seen, without loading the row.
     * @param stripeEventId Stripe's {@code evt_} identifier
     * @return true if it is already in the ledger
     */
    boolean existsByStripeEventId(String stripeEventId);

    /**
     * Every event about one Stripe object, newest first.
     *
     * <p>What explains a mirrored row's current state: a payment that says something unexpected is
     * a question about the sequence of events applied to it, and this is that sequence.</p>
     *
     * @param objectId Stripe's id for the object, e.g. a {@code pi_}
     * @return the events about it
     */
    List<StripeEvent> findByObjectIdOrderByCreatedAtDesc(String objectId);

    /**
     * When the oldest event still waiting to be applied arrived.
     *
     * <p>The age and not the count, for the reason common-amqp's outbox indicator gives: a large
     * backlog seconds old is a busy service, while one row an hour old is a broken one. This is
     * what a health indicator reports, and it is the single most useful number about this
     * service - a webhook that is never applied is a payment the platform does not know about.</p>
     *
     * <p>Written out rather than derived, as in {@code NotificationRepository}: Spring Data's
     * method names cannot select a single column, and {@code MIN} answers the question without
     * ordering and loading a row to read one field off it.</p>
     *
     * @param statuses the unapplied states, which are {@link StripeEventStatus#RECEIVED} and
     *                 {@link StripeEventStatus#FAILED} - the two {@code ix_stripe_events_unapplied}
     *                 is partial on, so asking for any others scans the table
     * @return when the oldest such event was received, or empty when there are none
     */
    @Query("SELECT MIN(e.createdAt) FROM StripeEvent e WHERE e.status IN :statuses")
    Optional<Instant> findOldestUnappliedCreatedAt(
            @Param("statuses") Collection<StripeEventStatus> statuses);
}
