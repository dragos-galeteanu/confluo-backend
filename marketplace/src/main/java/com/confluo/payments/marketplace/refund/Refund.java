package com.confluo.payments.marketplace.refund;

import com.confluo.common.jpa.entity.BaseJpaEntity;
import com.confluo.payments.marketplace.Payment;
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
 * Money sent back.
 *
 * <p>One row per refund, not per payment: a payment can be refunded in parts, by different people,
 * for different reasons, and {@link #initiatedBy} is the only record of which.</p>
 *
 * <p>{@link #reversedTransferAmount} is where being merchant of record costs something. Refunding a
 * destination charge means taking the seller's share back off their Stripe balance, and if it has
 * already been paid out there may be nothing there - the platform absorbs the difference. This is
 * the number that says how much was actually recovered.</p>
 */
@Getter
@Setter
@Entity
@Table(
        name = "refunds",
        uniqueConstraints = @UniqueConstraint(name = "uq_refunds_stripe_id", columnNames = "stripe_refund_id"))
public class Refund extends BaseJpaEntity {

    /** what is being refunded */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payment_id", nullable = false)
    private Payment payment;

    /** Stripe's own {@code re_} identifier */
    @Column(name = "stripe_refund_id", nullable = false, length = 255)
    private String stripeRefundId;

    /** how much, in the currency's minor units */
    @Column(name = "amount", nullable = false)
    private long amount;

    /** uppercase ISO 4217 */
    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    /**
     * Stripe's own reason, as free text rather than an enum: they add values, and a constraint here
     * would reject the webhook rather than the reason.
     */
    @Column(name = "reason", length = 64)
    private String reason;

    /** where it has got to. FAILED is real - a card closed since the charge bounces the refund */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private RefundStatus status;

    /** who decided it should happen */
    @Enumerated(EnumType.STRING)
    @Column(name = "initiated_by", nullable = false, length = 16)
    private RefundInitiator initiatedBy;

    /** what was actually clawed back from the seller's balance, which can be less than their share */
    @Column(name = "reversed_transfer_amount")
    private Long reversedTransferAmount;

    /** why, in the words of whoever issued it */
    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    /** the timestamp of the most recent Stripe event applied to this row */
    @Column(name = "last_stripe_event_at")
    private Instant lastStripeEventAt;
}
