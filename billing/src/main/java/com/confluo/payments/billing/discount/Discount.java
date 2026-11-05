package com.confluo.payments.billing.discount;

import com.confluo.common.jpa.entity.BaseJpaEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Money off, however it came to be granted.
 *
 * <p>One entity for the four ways somebody gets a discount, because what a discount <em>is</em>
 * does not differ between them - only how it is reached. A public code is typed in at checkout;
 * a referral, a goodwill credit and a cohort grant are attached to one person by this platform,
 * which is why {@link #code} is nullable: giving a goodwill credit a code would make it redeemable
 * by anybody who learned the string.</p>
 *
 * <p><strong>Several of the rules about this shape live only in the migration.</strong> That a
 * discount is a percentage or an amount and never both, that a fixed amount has a currency, that a
 * repeating discount states its length - all of it is a {@code CHECK} constraint, because a service
 * enforcing it alone is one refactor away from a coupon that reduces nothing, and Stripe would then
 * reject it at redemption, in front of the payer.</p>
 *
 * <p>{@link #maxRedemptionsPerCustomer} is the one limit Stripe's coupon cannot express. It is
 * enforced here against {@link DiscountRedemption} before a session is created.</p>
 */
@Getter
@Setter
@Entity
@Table(name = "discounts")
public class Discount extends BaseJpaEntity {

    /**
     * What a payer types, uppercase, or null for a discount nobody can redeem by name. Uppercase
     * because Stripe matches promotion codes case-insensitively, and a column holding both
     * WELCOME10 and welcome10 has two rows for one code and finds whichever it happens to.
     */
    @Column(name = "code", length = 64)
    private String code;

    /** how somebody comes to have it */
    @Enumerated(EnumType.STRING)
    @Column(name = "grant_kind", nullable = false, length = 16)
    private DiscountGrant grantKind;

    /** whether it takes a proportion off or an amount */
    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 16)
    private DiscountKind kind;

    /** the proportion, to two decimal places, when {@link #kind} is PERCENTAGE */
    @Column(name = "percent_off", precision = 5, scale = 2)
    private BigDecimal percentOff;

    /** the amount in minor units, when {@link #kind} is FIXED */
    @Column(name = "amount_off")
    private Long amountOff;

    /** the currency {@link #amountOff} is in, and meaningless without it */
    @Column(name = "currency", length = 3)
    private String currency;

    /** how long it keeps applying to a subscription */
    @Enumerated(EnumType.STRING)
    @Column(name = "duration", nullable = false, length = 16)
    private DiscountDuration duration;

    /** how many months, when {@link #duration} is REPEATING, and null otherwise */
    @Column(name = "duration_in_months")
    private Integer durationInMonths;

    /** how many times it may be used across everybody, or null for no limit */
    @Column(name = "max_redemptions")
    private Integer maxRedemptions;

    /** how many times one person may use it, which Stripe cannot enforce and this service does */
    @Column(name = "max_redemptions_per_customer")
    private Integer maxRedemptionsPerCustomer;

    /** when it starts being valid, or null for immediately */
    @Column(name = "valid_from")
    private Instant validFrom;

    /** when it stops being valid, or null for never */
    @Column(name = "valid_until")
    private Instant validUntil;

    /** the smallest order it applies to, in minor units, or null for any */
    @Column(name = "minimum_amount")
    private Long minimumAmount;

    /** the currency {@link #minimumAmount} is in; the two are one fact and neither is valid alone */
    @Column(name = "minimum_amount_currency", length = 3)
    private String minimumAmountCurrency;

    /** whether it is only for somebody who has never paid before */
    @Column(name = "first_time_transaction_only", nullable = false)
    private boolean firstTimeTransactionOnly = false;

    /** which plans it may be used against; empty means any of them */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "applies_to_plan_ids", nullable = false, columnDefinition = "jsonb")
    private List<UUID> appliesToPlanIds = new ArrayList<>();

    /** Stripe's {@code coupon} identifier: the shape of the discount, reconciled from this row */
    @Column(name = "stripe_coupon_id", length = 255)
    private String stripeCouponId;

    /**
     * Stripe's {@code promo_} identifier: the string a payer can type. Only a redeemable discount
     * has one, so this stays null for the three granted kinds.
     */
    @Column(name = "stripe_promotion_code_id", length = 255)
    private String stripePromotionCodeId;
}
