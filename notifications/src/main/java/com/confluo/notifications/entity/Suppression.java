package com.confluo.notifications.entity;

import com.confluo.common.jpa.entity.BaseJpaEntity;
import com.confluo.notifications.channel.Channel;
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
 * A destination nothing may be sent to again.
 *
 * <p>Keyed by the destination itself rather than by a recipient, and deliberately so: an address
 * that hard-bounced or a number that answered STOP has to stay suppressed even when it is later
 * attached to a different uid. Keying this to a person would let a re-registration quietly reopen
 * it, which is the failure that turns into a spam complaint.</p>
 *
 * <p>This is also why it is one of the few things here with no {@link Recipient} on it at all. A
 * suppression may well exist for an address no recipient row has ever held.</p>
 */
@Getter
@Setter
@Entity
@Table(
        name = "suppressions",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_suppressions_destination",
                columnNames = {"channel", "destination"}))
public class Suppression extends BaseJpaEntity {

    /** the channel it applies to; an address and a number are suppressed independently */
    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 16)
    private Channel channel;

    /** the email address or E.164 number itself */
    @Column(name = "destination", nullable = false, length = 320)
    private String destination;

    /** why it is here */
    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, length = 16)
    private SuppressionReason reason;

    /**
     * When it became true, which is not when the row was written: a bounce notification can arrive
     * hours after the bounce, and a STOP is timestamped by the provider that received it.
     */
    @Column(name = "suppressed_at", nullable = false)
    private Instant suppressedAt;

    /** anything a person should know when they find this row and wonder */
    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;
}
