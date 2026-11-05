package com.confluo.amqp.event.payments;

import com.confluo.amqp.event.DomainEvent;

import java.util.UUID;

/**
 * A subscription is over, and whatever it granted is withdrawn.
 *
 * <p>The event to revoke an entitlement on. It covers both ways a subscription ends - somebody
 * cancelling, and Stripe giving up after its retries - because the consequence is the same and a
 * consumer that distinguished them would do so only to word a message differently.</p>
 *
 * <p>Note what this is <strong>not</strong>: a subscription with {@code cancelAtPeriodEnd} set is
 * still live and still paid for, and announcing it here would take away a product somebody has paid
 * for until the end of the month.</p>
 *
 * @param subscriptionId the subscription, which is also the envelope's aggregate id
 * @param firebaseUid whose it was
 * @param planKey which tier, by its stable name
 * @param audience which side of the marketplace the tier was sold to
 * @param unpaid whether it ended because collection failed rather than because somebody cancelled
 */
@DomainEvent(aggregate = "subscription", event = "ended")
public record SubscriptionEndedEvent(
        UUID subscriptionId,
        String firebaseUid,
        String planKey,
        String audience,
        boolean unpaid) {
}
