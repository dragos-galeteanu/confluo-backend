package com.confluo.amqp.event.payments;

import com.confluo.amqp.event.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * Somebody is now entitled to a plan.
 *
 * <p>Published when a subscription first becomes live - trialing or active. Both count, which is the
 * point: a trial grants everything the plan grants, and a consumer that waits for {@code active}
 * withholds a product somebody has been told they have.</p>
 *
 * <p>{@code planKey} rather than a plan id, because the key is the name written down outside the
 * payments service - an id of its own is something only it knows. {@code entitlements} is
 * deliberately absent: it is the plan's, it changes without any subscription changing, and a
 * consumer that needs it should read the plan rather than a copy frozen at signup.</p>
 *
 * @param subscriptionId the subscription, which is also the envelope's aggregate id
 * @param firebaseUid whose it is
 * @param planKey which tier, by its stable name
 * @param audience which side of the marketplace the tier is sold to, {@code PROVIDER} or
 *                 {@code CUSTOMER}
 * @param trialing whether it starts in a trial rather than paid
 * @param currentPeriodEnd when it next renews, or when the trial ends
 */
@DomainEvent(aggregate = "subscription", event = "started")
public record SubscriptionStartedEvent(
        UUID subscriptionId,
        String firebaseUid,
        String planKey,
        String audience,
        boolean trialing,
        Instant currentPeriodEnd) {
}
