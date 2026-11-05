package com.confluo.payments.pricing;

import com.confluo.common.jpa.entity.BaseJpaEntity;
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

/**
 * What a plan costs, in one currency, over one billing period.
 *
 * <p><strong>A row here is never updated to change a price.</strong> A Stripe price is immutable,
 * so changing what a plan costs means inserting a row and clearing {@link #active} on the old one -
 * and that is also the honest model, because subscriptions already running are on the old price and
 * have to stay queryable. A unique partial index allows only one active row per plan, currency and
 * period.</p>
 *
 * <p>{@link #unitAmount} is in the currency's minor units, as Stripe states it. Zero is allowed:
 * a free tier is a real plan, and modelling it as the absence of a price makes every reader handle
 * a subscription with nothing to charge.</p>
 */
@Getter
@Setter
@Entity
@Table(name = "plan_prices")
public class PlanPrice extends BaseJpaEntity {

    /** the tier this is a price for */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "plan_id", nullable = false)
    private Plan plan;

    /** uppercase ISO 4217, as the platform service spells its currency codes */
    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    /** the amount per period, in the currency's minor units */
    @Column(name = "unit_amount", nullable = false)
    private long unitAmount;

    /** whether the period is a month or a year */
    @Enumerated(EnumType.STRING)
    @Column(name = "billing_interval", nullable = false, length = 8)
    private BillingInterval billingInterval;

    /** how many of those periods one charge covers, e.g. 3 with MONTH for quarterly billing */
    @Column(name = "interval_count", nullable = false)
    private int intervalCount = 1;

    /**
     * Days free before the first charge, or null for no trial. A trial is not a discount and is not
     * stored as one: nothing is reduced, the clock simply starts later.
     */
    @Column(name = "trial_period_days")
    private Integer trialPeriodDays;

    /** Stripe's own {@code price_} identifier, or null until the catalog has been reconciled */
    @Column(name = "stripe_price_id", length = 255)
    private String stripePriceId;

    /**
     * Whether this is the price a new subscription is sold at. Cleared rather than deleted when a
     * price changes, because the subscriptions still on it refer to this row.
     */
    @Column(name = "active", nullable = false)
    private boolean active = true;
}
