package com.confluo.webhook;

/**
 * Which of the two webhook endpoints a request arrived at, and so which secret verifies it.
 *
 * <p>Stripe issues one signing secret per registered endpoint, and these are two endpoints: the
 * platform's own events and its connected accounts' events. The secrets are not interchangeable -
 * verifying with the wrong one fails every request rather than some of them - which is why the
 * route picks the source rather than the service guessing from the payload.</p>
 */
public enum StripeWebhookSource {

    /** the platform account's own events: payments, subscriptions, invoices, disputes */
    PLATFORM,

    /** events about connected accounts: onboarding state, capabilities, transfers, payouts */
    CONNECT
}
