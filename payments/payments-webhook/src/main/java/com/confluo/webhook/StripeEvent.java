package com.confluo.webhook;

import com.confluo.common.jpa.entity.BaseJpaEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * One webhook Stripe has sent, and whether it has been applied.
 *
 * <p><strong>This is the idempotency guarantee.</strong> A row is inserted the moment an event's
 * signature verifies and <em>before</em> anything acts on it, so the unique constraint on
 * {@link #stripeEventId} - and not a handler's own care - is what makes applying one twice
 * impossible. Stripe retries a webhook for days and will redeliver an event it has already
 * delivered successfully; without this, a redelivered {@code charge.succeeded} is a second delivery
 * of whatever was bought.</p>
 *
 * <p>{@code createdAt} <strong>is</strong> the moment the event was received, because the row is
 * written immediately on arrival - which is why there is no {@code receivedAt} beside it: one fact
 * in two columns is how the two come to disagree. {@link #processedAt} is when it was applied,
 * which is a separate transaction and can be much later if the first attempt failed.</p>
 *
 * <p>The event's raw body is not here. It is a
 * {@link com.confluo.webhook.payload.StripeEventPayload} in MongoDB keyed by
 * {@link #stripeEventId}, for the reason the notifications service keeps rendered bodies out of its
 * own queue: the sweep over this table runs constantly and reads only the state columns, while a
 * payload is kilobytes it does not look at.</p>
 */
@Getter
@Setter
@Entity
@Table(
        name = "stripe_events",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_stripe_events_stripe_id",
                columnNames = "stripe_event_id"))
public class StripeEvent extends BaseJpaEntity {

    /** Stripe's own {@code evt_} identifier, and the whole point of the table */
    @Column(name = "stripe_event_id", nullable = false, length = 255)
    private String stripeEventId;

    /** what happened, as Stripe names it, e.g. {@code payment_intent.succeeded} */
    @Column(name = "event_type", nullable = false, length = 128)
    private String eventType;

    /**
     * The API version the event was rendered at, which is not necessarily the one this service
     * pins: an endpoint can be registered at an older version, and an event whose version is not
     * the expected one is worth noticing before its contents are trusted.
     */
    @Column(name = "api_version", length = 32)
    private String apiVersion;

    /**
     * Whether this came from live mode. Stored because a test event arriving at a production
     * endpoint, or the reverse, is otherwise invisible and explains an enormous amount.
     */
    @Column(name = "livemode", nullable = false)
    private boolean livemode;

    /**
     * The Stripe account the event concerns, or null for the platform's own.
     *
     * <p>Stripe's {@code acct_} id as a plain string, deliberately not an association to
     * {@code ConnectedAccount}: an event can arrive for an account this service has no row for, and
     * a foreign key would refuse exactly the event that explains why.</p>
     */
    @Column(name = "stripe_account_id", length = 255)
    private String stripeAccountId;

    /**
     * The id of the object the event is about, so a row can be found from the payment rather than
     * only the other way round - which is how "why does this payment say that?" is answered.
     */
    @Column(name = "object_id", length = 255)
    private String objectId;

    /**
     * When Stripe says the event occurred - its own {@code created}, not the object's.
     *
     * <p>What a handler compares against the {@code lastStripeEventAt} of the row it is about to
     * write, because webhooks have no ordering guarantee and an older event must not overwrite a
     * newer one. It has to be the event's timestamp: the object's creation time is the same for
     * every event about that object, so it would order nothing.</p>
     */
    @Column(name = "event_created_at")
    private Instant eventCreatedAt;

    /** when it was applied, or null while it has not been */
    @Column(name = "processed_at")
    private Instant processedAt;

    /** where it has got to in this service */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private StripeEventStatus status;

    /** how many times applying it has been attempted, which the sweep's backoff reads */
    @Column(name = "attempts", nullable = false)
    private int attempts = 0;

    /** what went wrong on the last attempt, for the person fixing it before the replay */
    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    /**
     * When the sweep may next try to apply this.
     *
     * <p>{@code now()} on a freshly received event, so it is due at once, and moved forward by a
     * doubling backoff on each failure - which is what stops a handler that throws from being
     * retried as fast as the sweep can run.</p>
     *
     * <p><strong>Read-only to JPA</strong>, and that is what makes the above true. The column's
     * {@code DEFAULT now()} sets it on insert and the sweep's own SQL moves it on failure, both on
     * the database's clock - so a skewed instance cannot make an event due early or late. Leaving
     * it insertable would have Hibernate write a null into a {@code NOT NULL} column on every
     * ledger insert, because it includes every mapped column in the statement whether the field
     * was set or not.</p>
     */
    @Column(name = "next_attempt_at", nullable = false, insertable = false, updatable = false)
    private Instant nextAttemptAt;
}
