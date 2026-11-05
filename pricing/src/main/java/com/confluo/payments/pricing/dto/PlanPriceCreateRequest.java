package com.confluo.payments.pricing.dto;

import com.confluo.payments.pricing.BillingInterval;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * Add a price to a tier.
 *
 * <p>Adding, never changing: a Stripe price is immutable, so this is also how a tier's price is
 * changed - the new price becomes the active one and the old is deactivated, which keeps the
 * subscriptions already on it readable at what they are actually paying.</p>
 *
 * <p>The tier comes from the route, not the body.</p>
 *
 * @param currency uppercase ISO 4217
 * @param unitAmount the amount per period, in the currency's minor units. Zero is allowed: a free
 *                   tier is a real tier, and modelling it as the absence of a price makes every
 *                   reader handle a subscription with nothing to charge
 * @param billingInterval whether the period is a month or a year
 * @param intervalCount how many of those periods one charge covers, e.g. 3 months for quarterly
 * @param trialPeriodDays days free before the first charge, or null for no trial
 */
public record PlanPriceCreateRequest(

        @NotBlank
        @Pattern(regexp = "^[A-Z]{3}$", message = "must be an uppercase ISO 4217 code")
        String currency,

        @PositiveOrZero
        long unitAmount,

        @NotNull
        BillingInterval billingInterval,

        @Min(1)
        int intervalCount,

        @Min(1)
        Integer trialPeriodDays
) {
}
