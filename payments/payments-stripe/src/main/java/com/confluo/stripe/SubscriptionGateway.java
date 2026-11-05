package com.confluo.stripe;

import java.time.Instant;

/**
 * What this service does to a Stripe subscription after it exists.
 *
 * <p>A seam, as the other gateways are. The decisions in front of it - which price a subscriber may
 * move to, whether a cancellation takes effect now or at the period end, and that the local row is
 * left for the webhook to write - are the part worth exercising.</p>
 *
 * <p>{@link #changePrice} takes only the new price because finding the subscription's existing item
 * is Stripe's business, not a decision: a subscription sold here has exactly one item, and the
 * caller has nothing to say about which.</p>
 */
public interface SubscriptionGateway {

    /**
     * Moves a subscription onto a different price, charging the difference now.
     *
     * <p>Immediately rather than at the next renewal, which is what this platform chose: an upgrade
     * somebody paid for should start at once, and the proration appears on an invoice raised there
     * and then.</p>
     *
     * @param stripeSubscriptionId Stripe's {@code sub_} identifier
     * @param newStripePriceId the price to move to
     * @return the subscription as Stripe now has it
     */
    SubscriptionSnapshot changePrice(String stripeSubscriptionId, String newStripePriceId);

    /**
     * Ends a subscription.
     *
     * @param stripeSubscriptionId Stripe's {@code sub_} identifier
     * @param atPeriodEnd true to let it run to the end of what has been paid for, false to end it
     *                    now. Ending it now forfeits the remainder: Stripe raises no credit note
     *                    for the unused days, so this is the destructive one
     * @return the subscription as Stripe now has it
     */
    SubscriptionSnapshot cancel(String stripeSubscriptionId, boolean atPeriodEnd);

    /**
     * Calls off a cancellation that was scheduled for the period end.
     *
     * <p>Only possible while the subscription is still running. Once the period has closed there is
     * nothing to resume, and a new subscription is the only way back.</p>
     *
     * @param stripeSubscriptionId Stripe's {@code sub_} identifier
     * @return the subscription as Stripe now has it
     */
    SubscriptionSnapshot resume(String stripeSubscriptionId);

    /**
     * What Stripe says about a subscription.
     *
     * <p>The period comes from the subscription's first <strong>item</strong>, not from the
     * subscription: Stripe moved those fields and reading them off the subscription silently yields
     * nothing.</p>
     *
     * @param stripeSubscriptionId Stripe's own identifier
     * @param status Stripe's own status string
     * @param stripePriceId the price it is now billed at
     * @param cancelAtPeriodEnd whether it stops at the end of the current period
     * @param currentPeriodStart the start of the period paid for, or null
     * @param currentPeriodEnd the end of it, or null
     * @param canceledAt when it ended, or null
     * @param trialEnd when the trial ends, or null
     */
    record SubscriptionSnapshot(
            String stripeSubscriptionId,
            String status,
            String stripePriceId,
            boolean cancelAtPeriodEnd,
            Instant currentPeriodStart,
            Instant currentPeriodEnd,
            Instant canceledAt,
            Instant trialEnd) {
    }
}
