package com.confluo.payments.payouts.dispute;

import com.confluo.common.jpa.entity.BaseJpaEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;


/**
 * A chargeback.
 *
 * <p>Worth understanding before reading the fields: under destination charges <strong>this platform
 * is the merchant of record</strong>, so a dispute debits the platform's balance and not the
 * seller's. {@link #transferReversedAmount} is how much of the seller's share was recovered, and it
 * can be less than the dispute - if their balance has already been paid out, the platform absorbs
 * the rest.</p>
 *
 * <p>Recovering it is <strong>automatic</strong>: a dispute reaching {@code lost} reverses the
 * seller's transfer, rather than the platform holding a reserve against every seller to cover the
 * few who attract chargebacks. {@code DisputeClawback} does it and this field records the result,
 * so a zero here on a lost dispute means the platform bore it - usually because the money had
 * already reached the seller's bank.</p>
 *
 * <p>{@link #status} and {@link #reason} are Stripe's own and are deliberately free text rather
 * than enums. They extend both, and a constraint here would reject the webhook rather than the
 * value - losing an event about a dispute, which has a deadline, to tidiness.</p>
 */
@Getter
@Setter
@Entity
@Table(
        name = "disputes",
        uniqueConstraints = @UniqueConstraint(name = "uq_disputes_stripe_id", columnNames = "stripe_dispute_id"))
public class Dispute extends BaseJpaEntity {

    /**
     * The payment being disputed - its id, not the payment.
     *
     * <p>{@code Payment} belongs to marketplace. The handler loads it by this id when it needs it,
     * which is once per event and explicit, rather than through an association that works inside
     * the sweep's transaction and throws anywhere else.</p>
     */
    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    /** Stripe's own {@code dp_} identifier */
    @Column(name = "stripe_dispute_id", nullable = false, length = 255)
    private String stripeDisputeId;

    /** how much is being claimed back, in minor units */
    @Column(name = "amount", nullable = false)
    private long amount;

    /** uppercase ISO 4217 */
    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    /** Stripe's reason, e.g. {@code fraudulent} or {@code product_not_received} */
    @Column(name = "reason", length = 64)
    private String reason;

    /** Stripe's status, e.g. {@code needs_response} or {@code lost} */
    @Column(name = "status", nullable = false, length = 32)
    private String status;

    /**
     * The deadline for submitting evidence. A field and not something to be read out of Stripe's
     * dashboard when somebody thinks to look: missing it loses the dispute by default.
     */
    @Column(name = "evidence_due_by")
    private Instant evidenceDueBy;

    /** whether refunding the charge is still possible, which it is not once funds are withdrawn */
    @Column(name = "is_charge_refundable", nullable = false)
    private boolean chargeRefundable = false;

    /** how much of the seller's share was actually clawed back */
    @Column(name = "transfer_reversed_amount", nullable = false)
    private long transferReversedAmount = 0;

    /** the timestamp of the most recent Stripe event applied to this row */
    @Column(name = "last_stripe_event_at")
    private Instant lastStripeEventAt;
}
