package com.confluo.payments.billing.discount;

import com.confluo.common.jpa.entity.BaseJpaEntity;
import com.confluo.payments.billing.subscription.Subscription;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;


/**
 * One use of a discount, by one person.
 *
 * <p>This is where the per-person limit lives. Stripe's coupon can cap total redemptions and cannot
 * cap them per customer, so that rule is enforced against this table before a session is created -
 * which is the whole reason the table exists rather than the count being read out of Stripe.</p>
 *
 * <p>Keyed to the Firebase uid rather than to the Stripe customer, because the limit is about a
 * person and a person can be given a new Stripe customer by any number of mistakes.</p>
 *
 * <p>Exactly one of {@link #subscription} and {@link #paymentId} is set, which the migration
 * enforces: a discount is redeemed against a recurring charge or a single one, and which it was is
 * the only way to find what it actually reduced.</p>
 */
@Getter
@Setter
@Entity
@Table(name = "discount_redemptions")
public class DiscountRedemption extends BaseJpaEntity {

    /** what was redeemed */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "discount_id", nullable = false)
    private Discount discount;

    /** who redeemed it */
    @Column(name = "firebase_uid", nullable = false, length = 128)
    private String firebaseUid;

    /** the subscription it was applied to, or null if it was a one-off payment */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "subscription_id")
    private Subscription subscription;

    /**
     * The payment it was applied to - its id - or null if it was a subscription.
     *
     * <p>The id rather than the payment, because {@code Payment} belongs to marketplace. Nothing
     * writes it yet: discounts are sold on subscriptions today, and this column is what a one-off
     * discount would be recorded in.</p>
     */
    @Column(name = "payment_id")
    private UUID paymentId;

    /**
     * When it was used, which is not {@code createdAt} in the case that matters: a redemption
     * recorded from a webhook happened when Stripe says it did, not when this row was written.
     */
    @Column(name = "redeemed_at", nullable = false)
    private Instant redeemedAt;
}
