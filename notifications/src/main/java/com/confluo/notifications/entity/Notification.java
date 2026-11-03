package com.confluo.notifications.entity;

import com.confluo.common.jpa.entity.BaseJpaEntity;
import com.confluo.notifications.channel.Category;
import com.confluo.notifications.channel.Channel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * One message that should exist, and the state it has got to.
 *
 * <p>Written by the pipeline inside the transaction that decided to send, and claimed afterwards by
 * the dispatcher. That is the same shape as the outbox and holds for the same reason: nothing
 * reaches a provider before the decision to send it has committed, and nothing is marked sent until
 * a provider has taken it.</p>
 *
 * <p><strong>The content is not here.</strong> The rendered subject and body are a document in
 * MongoDB keyed by this row's id. The dispatcher's claim query runs constantly and reads only the
 * state columns below, while an HTML body is kilobytes it does not look at until the moment it
 * sends - so keeping bodies out of this table is what keeps these rows narrow and
 * {@code ix_notifications_due} small.</p>
 *
 * <p>{@link #destination} and the chosen {@link #locale} are frozen here rather than resolved again
 * at send time. If someone changes their email afterwards, this row still says where this message
 * went, which is the only answer worth having about a message already sent.</p>
 */
@Getter
@Setter
@Entity
@Table(name = "notifications")
public class Notification extends BaseJpaEntity {

    /** whom it is for, or null for an ad-hoc send that named a raw destination and no person */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recipient_id")
    private Recipient recipient;

    /** how it goes */
    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 16)
    private Channel channel;

    /** what kind of message it is, and so whether a preference could have stopped it */
    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 16)
    private Category category;

    /** which template it was rendered from */
    @Column(name = "template_key", nullable = false, length = 128)
    private String templateKey;

    /** the locale actually rendered, after the fallback chain ran - not the one asked for */
    @Column(name = "locale", nullable = false, length = 16)
    private String locale;

    /** the address or number it was sent to, frozen at render time */
    @Column(name = "destination", nullable = false, length = 320)
    private String destination;

    /** where it has got to */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private NotificationStatus status;

    /** how many provider calls have been made for it */
    @Column(name = "attempts", nullable = false)
    private int attempts = 0;

    /** when the dispatcher should next consider it; the backoff is written into this */
    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    /**
     * When it should not be sent before. Nothing sets it yet - quiet hours would - and
     * {@link #nextAttemptAt} is what the dispatcher actually reads, so a scheduled message is one
     * whose first {@code nextAttemptAt} is this.
     */
    @Column(name = "scheduled_for")
    private Instant scheduledFor;

    /** what went wrong on the last attempt, for a person reading the log */
    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    /**
     * What must not be sent twice, <strong>ever</strong> - not a time window. {@code welcome:<uid>}
     * is the shape of it; a caller that wants a repeatable send passes null. A unique partial index
     * is what enforces it; the deduplication window in Redis is an optimisation in front of that,
     * so losing Redis costs a wasted insert rather than a duplicate message.
     */
    @Column(name = "dedup_key", length = 255)
    private String dedupKey;

    /** what ties this message to a wider conversation, when something set one */
    @Column(name = "correlation_id", length = 128)
    private String correlationId;

    /**
     * The envelope's {@code eventId}, when an event asked for this rather than an API call. It is
     * what traces a message back to the thing that happened.
     */
    @Column(name = "trigger_event_id")
    private UUID triggerEventId;

    /** the Firebase uid or the service name behind the request */
    @Column(name = "requested_by", length = 128)
    private String requestedBy;
}
