package com.confluo.payments.billing.discount.dto;

import com.confluo.payments.billing.discount.DiscountDuration;
import com.confluo.payments.billing.discount.DiscountGrant;
import com.confluo.payments.billing.discount.DiscountKind;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A discount, as the API returns it.
 *
 * <p>{@code stripeCouponId} and {@code stripePromotionCodeId} are returned rather than hidden, for
 * the reason a plan's product id is: null means the discount was not pushed to Stripe, which is the
 * difference between one that can be redeemed and one that cannot.</p>
 *
 * <p>{@code timesRedeemed} is counted from this service's own redemption rows rather than read from
 * Stripe. It is what the per-person limit is enforced against, so it is the number that matters
 * here - and asking Stripe would make listing discounts a page of network calls.</p>
 *
 * @param id this service's id
 * @param code what a payer types, or null for a granted discount
 * @param grantKind how somebody comes to have it
 * @param kind whether it takes a proportion off or an amount
 * @param percentOff the proportion, when kind is PERCENTAGE
 * @param amountOff the amount in minor units, when kind is FIXED
 * @param currency the currency of amountOff
 * @param duration how long it applies to a subscription
 * @param durationInMonths how many months, when duration is REPEATING
 * @param maxRedemptions the limit across everybody, or null
 * @param maxRedemptionsPerCustomer the per-person limit, or null
 * @param timesRedeemed how many times it has been used, counted here
 * @param validFrom when it starts being valid, or null
 * @param validUntil when it stops, or null
 * @param minimumAmount the smallest order it applies to, or null
 * @param minimumAmountCurrency the currency of that threshold
 * @param firstTimeTransactionOnly whether it is only for a first purchase
 * @param appliesToPlanIds which tiers it may be used against; empty means any
 * @param stripeCouponId Stripe's coupon id, or null if it was not reconciled
 * @param stripePromotionCodeId Stripe's promotion code id, or null
 * @param disabledAt when it was withdrawn, or null
 * @param createdAt when it was created
 */
public record DiscountResponse(
        UUID id,
        String code,
        DiscountGrant grantKind,
        DiscountKind kind,
        BigDecimal percentOff,
        Long amountOff,
        String currency,
        DiscountDuration duration,
        Integer durationInMonths,
        Integer maxRedemptions,
        Integer maxRedemptionsPerCustomer,
        long timesRedeemed,
        Instant validFrom,
        Instant validUntil,
        Long minimumAmount,
        String minimumAmountCurrency,
        boolean firstTimeTransactionOnly,
        List<UUID> appliesToPlanIds,
        String stripeCouponId,
        String stripePromotionCodeId,
        Instant disabledAt,
        Instant createdAt
) {
}
