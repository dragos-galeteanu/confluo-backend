package com.confluo.payments.billing.subscription.dto;

import com.confluo.payments.pricing.BillingInterval;
import com.confluo.payments.billing.subscription.SubscriptionStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * A subscription, as the API returns it.
 *
 * <p>After a change or a cancellation this is built from <strong>Stripe's answer</strong> rather
 * than from the stored row, which has not been updated yet: the webhook is the only thing that
 * writes mirrored state, and it arrives moments later. So a caller gets the truth immediately and
 * the row catches up - see {@code SubscriptionManagementService} for why it is done that way round
 * rather than writing both.</p>
 *
 * <p>{@code entitled} is the field most consumers want and the one easiest to get wrong by hand:
 * trialing counts, and past due does not.</p>
 *
 * @param id this service's id for the subscription
 * @param firebaseUid whose it is
 * @param planKey which tier, by its stable name
 * @param status where it has got to
 * @param entitled whether it currently grants what the plan promises
 * @param unitAmount what it costs per period, in minor units
 * @param currency uppercase ISO 4217
 * @param billingInterval month or year
 * @param intervalCount how many of those one charge covers
 * @param currentPeriodStart the start of the period paid for, or null
 * @param currentPeriodEnd when it next renews, or when it lapses, or null
 * @param cancelAtPeriodEnd whether it stops at the end of this period
 * @param canceledAt when it ended, or null
 * @param trialEnd when the trial ends, or null
 */
public record SubscriptionResponse(
        UUID id,
        String firebaseUid,
        String planKey,
        SubscriptionStatus status,
        boolean entitled,
        long unitAmount,
        String currency,
        BillingInterval billingInterval,
        int intervalCount,
        Instant currentPeriodStart,
        Instant currentPeriodEnd,
        boolean cancelAtPeriodEnd,
        Instant canceledAt,
        Instant trialEnd
) {
}
