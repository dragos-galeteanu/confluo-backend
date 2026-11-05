package com.confluo.payments.pricing.dto;

import com.confluo.payments.pricing.BillingInterval;

import java.time.Instant;
import java.util.UUID;

/**
 * A price, as the API returns it.
 *
 * @param id this service's id for the price
 * @param planId the tier it prices
 * @param planKey that tier's stable name, so a caller holding a price need not fetch the plan
 * @param currency uppercase ISO 4217
 * @param unitAmount the amount per period, in minor units
 * @param billingInterval month or year
 * @param intervalCount how many of those one charge covers
 * @param trialPeriodDays days free before the first charge, or null
 * @param stripePriceId Stripe's own id, or null if it has not been reconciled - in which case
 *                      nothing can be subscribed at this price
 * @param active whether a new subscription would be sold at it
 * @param createdAt when it was added
 */
public record PlanPriceResponse(
        UUID id,
        UUID planId,
        String planKey,
        String currency,
        long unitAmount,
        BillingInterval billingInterval,
        int intervalCount,
        Integer trialPeriodDays,
        String stripePriceId,
        boolean active,
        Instant createdAt
) {
}
