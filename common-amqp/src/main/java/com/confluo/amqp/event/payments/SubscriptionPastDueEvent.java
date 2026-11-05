package com.confluo.amqp.event.payments;

import com.confluo.amqp.event.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * A subscription's renewal did not go through.
 *
 * <p>Still a subscription, not yet a cancellation: Stripe is retrying on its own schedule, and the
 * window this opens is when somebody can be asked to fix their card before anything is taken away.
 * Whether to keep serving them during it is the consumer's decision - the payments service holds no
 * opinion, and {@code Subscription.isEntitled()} says no, which is the safer default to be wrong
 * about.</p>
 *
 * <p><strong>This event carries no money, deliberately.</strong> It is published from a change to
 * the subscription, and a subscription does not know what is owed - the invoice does. A consumer
 * wording a dunning message wants {@code invoice.payment_failed}, which carries the amount, the
 * attempt number and when Stripe will try again. This one says only that an entitlement is at
 * risk, which is the part a consumer that grants access cares about.</p>
 *
 * @param subscriptionId the subscription, which is also the envelope's aggregate id
 * @param firebaseUid whose it is
 * @param planKey which tier, by its stable name
 * @param audience which side of the marketplace the tier is sold to
 * @param currentPeriodEnd the end of the period that was not renewed, or null
 */
@DomainEvent(aggregate = "subscription", event = "past_due")
public record SubscriptionPastDueEvent(
        UUID subscriptionId,
        String firebaseUid,
        String planKey,
        String audience,
        Instant currentPeriodEnd) {
}
