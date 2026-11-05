package com.confluo.notifications.entity;

import com.confluo.common.jpa.entity.BaseJpaEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * One call actually made to a provider.
 *
 * <p>Written before the outcome is known and updated when it is - by the call returning, and later
 * again by a provider's status callback, which finds this row through
 * {@link #providerMessageId}.</p>
 *
 * <p>Two of the five inherited columns earn their place here and two do not, which is worth stating
 * rather than leaving a reader to wonder. {@code createdAt} <strong>is</strong> the moment the
 * provider was called, because the row is written immediately before the call - so there is no
 * separate {@code startedAt}, which would be the same fact in a second column and would eventually
 * disagree with the first. {@code updatedAt} is the moment the outcome last changed, which is the
 * only record of <em>when</em> a status callback arrived: a message delivered hours after it was
 * sent moves this and nothing else. {@code disabledAt} and {@code deletedAt} are unused - an
 * attempt is a fact, and neither disabling nor soft-deleting a provider call that happened means
 * anything - though {@code deletedAt} is where a retention policy would eventually hide old rows
 * before purging them.</p>
 */
@Getter
@Setter
@Entity
@Table(
        name = "delivery_attempts",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_delivery_attempts_sequence",
                columnNames = {"notification_id", "attempt_no"}))
public class DeliveryAttempt extends BaseJpaEntity {

    /** the message this was an attempt at */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "notification_id", nullable = false)
    private Notification notification;

    /** which attempt this was, counting from one */
    @Column(name = "attempt_no", nullable = false)
    private int attemptNo;

    /**
     * Who was called - {@code GMAIL_SMTP} or {@code TWILIO} today. A string rather than an enum,
     * and the column carries no {@code CHECK}: which providers exist is operational, and adding one
     * should be an adapter and a setting rather than a migration, which is the whole reason sending
     * sits behind an interface.
     */
    @Column(name = "provider", nullable = false, length = 32)
    private String provider;

    /** what the provider called it, and how its status callback finds this row again */
    @Column(name = "provider_message_id", length = 255)
    private String providerMessageId;

    /** the verdict on this attempt */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private DeliveryStatus status;

    /** the provider's own code, e.g. Twilio's {@code 21610}, which the senders act on */
    @Column(name = "error_code", length = 64)
    private String errorCode;

    /** what it said, for a person reading the log */
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    /**
     * When the call came back, or null while it is in flight - which is how a process that died
     * mid-send is spotted. Paired with the inherited {@code createdAt}, those two are how long the
     * provider took.
     */
    @Column(name = "finished_at")
    private Instant finishedAt;
}
