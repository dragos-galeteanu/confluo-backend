package com.confluo.payments.billing.discount.dto;

import com.confluo.payments.billing.discount.DiscountDuration;
import com.confluo.payments.billing.discount.DiscountGrant;
import com.confluo.payments.billing.discount.DiscountKind;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Create money off.
 *
 * <p>There is no update request, and that is Stripe's constraint rather than a simplification: a
 * coupon's shape cannot be edited once it exists, only its name and metadata. So changing what a
 * discount is worth means creating another and withdrawing this one - the same arrangement as a
 * plan's price, and for the same underlying reason.</p>
 *
 * <p>Several combinations are refused by the service with a message naming the fields, and by
 * {@code CHECK} constraints underneath it: a percentage carries no amount or currency, a fixed
 * amount carries both, and only a repeating discount states a length. The constraints are the ones
 * that cannot be bypassed; the service's checks exist so the answer says what is wrong.</p>
 *
 * @param code what a payer types, uppercased by the service. Required for a {@code PUBLIC_CODE} and
 *             normally null for the rest: giving a goodwill credit a code makes it redeemable by
 *             anybody who learns the string
 * @param grantKind how somebody comes to have it
 * @param kind whether it takes a proportion off or an amount
 * @param percentOff the proportion, to two decimal places, when {@code kind} is PERCENTAGE
 * @param amountOff the amount in minor units, when {@code kind} is FIXED
 * @param currency the currency of {@code amountOff}, uppercase ISO 4217
 * @param duration how long it keeps applying to a subscription
 * @param durationInMonths how many months, required when {@code duration} is REPEATING
 * @param maxRedemptions how many times it may be used across everybody, or null for no limit
 * @param maxRedemptionsPerCustomer how many times one person may use it. The limit Stripe cannot
 *                                  express, enforced here before a session is created
 * @param validFrom when it starts being valid, or null for immediately
 * @param validUntil when it stops, or null for never
 * @param minimumAmount the smallest order it applies to, in minor units, or null for any
 * @param minimumAmountCurrency the currency of that threshold; required with it
 * @param firstTimeTransactionOnly whether it is only for somebody who has never paid before
 * @param appliesToPlanIds which tiers it may be used against; empty or null means any of them.
 *                         Each must already have been reconciled into Stripe, since the
 *                         restriction is expressed at Stripe as a list of products
 */
public record DiscountCreateRequest(

        @Size(max = 64)
        String code,

        @NotNull
        DiscountGrant grantKind,

        @NotNull
        DiscountKind kind,

        @DecimalMin(value = "0.01")
        @DecimalMax(value = "100.00")
        BigDecimal percentOff,

        @Positive
        Long amountOff,

        @Pattern(regexp = "^[A-Z]{3}$", message = "must be an uppercase ISO 4217 code")
        String currency,

        @NotNull
        DiscountDuration duration,

        @Min(1)
        Integer durationInMonths,

        @Min(1)
        Integer maxRedemptions,

        @Min(1)
        Integer maxRedemptionsPerCustomer,

        Instant validFrom,

        Instant validUntil,

        @Positive
        Long minimumAmount,

        @Pattern(regexp = "^[A-Z]{3}$", message = "must be an uppercase ISO 4217 code")
        String minimumAmountCurrency,

        boolean firstTimeTransactionOnly,

        List<UUID> appliesToPlanIds
) {
}
