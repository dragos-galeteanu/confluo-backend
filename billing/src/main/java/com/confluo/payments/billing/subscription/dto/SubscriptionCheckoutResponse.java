package com.confluo.payments.billing.subscription.dto;

import com.confluo.payments.pricing.BillingInterval;

/**
 * Where to send somebody to subscribe.
 *
 * <p><strong>No local identifier, and that is not an omission.</strong> Unlike an order checkout,
 * which commits a payment row before the payer is sent anywhere, a subscription does not exist
 * until Stripe creates it - there is no row to return an id for. What links the session back to
 * this platform is the metadata it carries, which {@code customer.subscription.created} reads to
 * build the local row.</p>
 *
 * <p>So a caller cannot treat a 200 here as somebody being subscribed. It means a session exists
 * and nothing more.</p>
 *
 * @param stripeCheckoutSessionId Stripe's {@code cs_} identifier
 * @param checkoutUrl Stripe's hosted page, where they actually subscribe
 * @param planKey the tier being subscribed to
 * @param unitAmount what it costs per period, in minor units, before tax and before any discount
 * @param currency uppercase ISO 4217
 * @param billingInterval month or year
 * @param intervalCount how many of those one charge covers
 * @param trialPeriodDays days free before the first charge, or null if the tier has no trial
 * @param appliedDiscountCode the code that was accepted, or null if none was given
 */
public record SubscriptionCheckoutResponse(
        String stripeCheckoutSessionId,
        String checkoutUrl,
        String planKey,
        long unitAmount,
        String currency,
        BillingInterval billingInterval,
        int intervalCount,
        Integer trialPeriodDays,
        String appliedDiscountCode
) {
}
