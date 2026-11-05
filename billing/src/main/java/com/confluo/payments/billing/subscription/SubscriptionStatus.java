package com.confluo.payments.billing.subscription;

/**
 * Where a subscription has got to, mirroring Stripe's own states.
 *
 * <p>Stripe Billing owns this state machine outright. Every value here is set by a webhook and
 * none by this service, because the renewal clock, the retry schedule behind
 * {@link #PAST_DUE} and the move to {@link #UNPAID} are all Stripe's - and a second copy of that
 * logic would be a second answer to whether somebody has paid.</p>
 *
 * <p>Only {@link #TRIALING} and {@link #ACTIVE} grant what the plan entitles someone to.
 * {@link #PAST_DUE} deliberately does not: it is the window in which Stripe is still retrying, and
 * what to do during it - keep serving, or stop - is a commercial decision that belongs to whoever
 * reads this, not to the enum.</p>
 */
public enum SubscriptionStatus {

    /** the first payment has not gone through yet, so the subscription is not started */
    INCOMPLETE,

    /** the first payment was never completed in time, and this subscription is dead */
    INCOMPLETE_EXPIRED,

    /** inside a free trial. Entitled, and not yet paying */
    TRIALING,

    /** paid and current */
    ACTIVE,

    /**
     * a renewal failed and Stripe is retrying it. Still a subscription, not yet a cancellation,
     * and the window every dunning message is sent during.
     */
    PAST_DUE,

    /** the retries ran out. Stripe has given up collecting, and nothing here is entitled */
    UNPAID,

    /** ended, whether by the subscriber, by this service, or by the retries running out */
    CANCELED,

    /** collection is paused by configuration, which is not the same as the subscriber stopping */
    PAUSED
}
