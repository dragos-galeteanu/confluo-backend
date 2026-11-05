package com.confluo.payments.billing.subscription;

import com.confluo.common.jpa.entity.BaseJpaEntity;
import com.confluo.payments.billing.discount.Discount;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;


/**
 * What somebody is subscribed to.
 *
 * <p><strong>Stripe Billing owns this state machine outright.</strong> The renewal clock, the retry
 * schedule behind {@link SubscriptionStatus#PAST_DUE} and the move to
 * {@link SubscriptionStatus#UNPAID} are all theirs, and every field below is written by a webhook.
 * A second implementation of any of it here would be a second answer to whether somebody has
 * paid.</p>
 *
 * <p>There is no uniqueness on {@link #firebaseUid}. One person can hold a provider plan and a
 * customer plan at the same time - both sides of this marketplace subscribe - and that is two
 * subscriptions rather than a conflict.</p>
 *
 * <p>{@link #planPriceId} is a price and not a plan, deliberately: a plan's price changes by
 * adding a row, so this says what they are actually paying rather than what the tier costs
 * today.</p>
 */
@Getter
@Setter
@Entity
@Table(
        name = "subscriptions",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_subscriptions_stripe_id",
                columnNames = "stripe_subscription_id"))
public class Subscription extends BaseJpaEntity {

    /** whose subscription it is */
    @Column(name = "firebase_uid", nullable = false, length = 128)
    private String firebaseUid;

    /** Stripe's own {@code sub_} identifier */
    @Column(name = "stripe_subscription_id", nullable = false, length = 255)
    private String stripeSubscriptionId;

    /**
     * The price in force - its id - which is what they are paying rather than what the tier now
     * costs.
     *
     * <p>Not a {@code @ManyToOne} to {@code PlanPrice}, which belongs to pricing. The catalog is
     * read through {@code PlanPriceRepository#findByIdWithPlan}, by the two services that need a
     * price and its tier, and that is a change of habit worth noticing: the association used to be
     * walked wherever somebody happened to need it, which worked inside a transaction and threw
     * outside one - and the services here deliberately run outside one, because they call
     * Stripe.</p>
     *
     * <p>The discount below is a different case and stays an association: {@code Discount} is this
     * module's own.</p>
     */
    @Column(name = "plan_price_id", nullable = false)
    private UUID planPriceId;

    /** the discount applied, or null */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "discount_id")
    private Discount discount;

    /** where it has got to, as Stripe says */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private SubscriptionStatus status;

    /** the start of the period currently paid for */
    @Column(name = "current_period_start")
    private Instant currentPeriodStart;

    /** when it next renews, or when it lapses if collection has failed */
    @Column(name = "current_period_end")
    private Instant currentPeriodEnd;

    /** whether it stops at the end of this period rather than renewing */
    @Column(name = "cancel_at_period_end", nullable = false)
    private boolean cancelAtPeriodEnd = false;

    /** when it ended, or null while it has not */
    @Column(name = "canceled_at")
    private Instant canceledAt;

    /** when the free trial runs out, or null if there was none */
    @Column(name = "trial_end")
    private Instant trialEnd;

    /** the timestamp of the most recent Stripe event applied to this row */
    @Column(name = "last_stripe_event_at")
    private Instant lastStripeEventAt;

    /**
     * Whether this subscription currently grants what its plan entitles.
     *
     * <p>Trialing and active, and nothing else. {@code PAST_DUE} deliberately does not count: it is
     * the window in which Stripe is still retrying, and whether to keep serving somebody during it
     * is a commercial decision - but the default has to be one of the two, and the safe one to get
     * wrong is the one that stops giving away a paid product.</p>
     *
     * @return true if the plan's entitlements apply
     */
    @Transient
    public boolean isEntitled() {
        return status == SubscriptionStatus.TRIALING || status == SubscriptionStatus.ACTIVE;
    }
}
