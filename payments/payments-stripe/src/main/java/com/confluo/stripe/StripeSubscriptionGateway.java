package com.confluo.stripe;

import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.Subscription;
import com.stripe.model.SubscriptionItem;
import com.stripe.param.SubscriptionUpdateParams;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;

/**
 * {@link SubscriptionGateway} against Stripe.
 *
 * <h2>{@code ALWAYS_INVOICE}, not {@code CREATE_PRORATIONS}</h2>
 *
 * <p>Both prorate. The difference is <em>when the money moves</em>, and it is the difference
 * between what this platform chose and what it did not:</p>
 *
 * <ul>
 *   <li>{@code CREATE_PRORATIONS} works out the credit and the charge and leaves them sitting as
 *       line items for the <strong>next</strong> renewal, which can be a month away;</li>
 *   <li>{@code ALWAYS_INVOICE} raises an invoice there and then, so an upgrade is paid for when it
 *       is taken.</li>
 * </ul>
 *
 * <p>"Prorate immediately" means the second. The first is the Stripe default, so getting this wrong
 * is a one-word omission that silently defers every upgrade's revenue by a billing cycle.</p>
 */
@RequiredArgsConstructor
@Slf4j
public class StripeSubscriptionGateway implements SubscriptionGateway {

    private final StripeClient stripe;

    @Override
    public SubscriptionSnapshot changePrice(String stripeSubscriptionId, String newStripePriceId) {
        try {
            Subscription current = stripe.subscriptions().retrieve(stripeSubscriptionId);
            SubscriptionItem item = firstItem(current);

            Subscription updated = stripe.subscriptions().update(stripeSubscriptionId,
                    SubscriptionUpdateParams.builder()
                            // The existing item is replaced in place. Adding a second and deleting
                            // the first would work too and would briefly bill for both.
                            .addItem(SubscriptionUpdateParams.Item.builder()
                                    .setId(item.getId())
                                    .setPrice(newStripePriceId)
                                    .build())
                            .setProrationBehavior(
                                    SubscriptionUpdateParams.ProrationBehavior.ALWAYS_INVOICE)
                            .build());

            log.info("Stripe subscription {} moved to price {}", stripeSubscriptionId, newStripePriceId);
            return snapshot(updated);
        } catch (StripeException e) {
            throw new StripeOperationException(
                    "Could not move subscription %s onto price %s"
                            .formatted(stripeSubscriptionId, newStripePriceId), e);
        }
    }

    @Override
    public SubscriptionSnapshot cancel(String stripeSubscriptionId, boolean atPeriodEnd) {
        try {
            Subscription result = atPeriodEnd
                    ? stripe.subscriptions().update(stripeSubscriptionId,
                            SubscriptionUpdateParams.builder()
                                    .setCancelAtPeriodEnd(true)
                                    .build())
                    // Ends it now. Stripe raises no credit note for the unused days, which is why
                    // this is the destructive branch and the caller has to ask for it.
                    : stripe.subscriptions().cancel(stripeSubscriptionId);

            log.info("Stripe subscription {} cancelled [atPeriodEnd={}]",
                    stripeSubscriptionId, atPeriodEnd);
            return snapshot(result);
        } catch (StripeException e) {
            throw new StripeOperationException(
                    "Could not cancel subscription %s".formatted(stripeSubscriptionId), e);
        }
    }

    @Override
    public SubscriptionSnapshot resume(String stripeSubscriptionId) {
        try {
            Subscription resumed = stripe.subscriptions().update(stripeSubscriptionId,
                    SubscriptionUpdateParams.builder()
                            .setCancelAtPeriodEnd(false)
                            .build());
            log.info("Stripe subscription {} will renew after all", stripeSubscriptionId);
            return snapshot(resumed);
        } catch (StripeException e) {
            throw new StripeOperationException(
                    "Could not resume subscription %s".formatted(stripeSubscriptionId), e);
        }
    }

    /**
     * The subscription's one item.
     *
     * @param subscription what Stripe sent
     * @return its first item
     * @throws IllegalStateException if it has none, which a subscription sold here never does
     */
    private SubscriptionItem firstItem(Subscription subscription) {
        if (subscription.getItems() == null
                || subscription.getItems().getData() == null
                || subscription.getItems().getData().isEmpty()) {
            throw new IllegalStateException(
                    "Stripe subscription %s has no items, so there is no price to change"
                            .formatted(subscription.getId()));
        }
        return subscription.getItems().getData().get(0);
    }

    private SubscriptionSnapshot snapshot(Subscription subscription) {
        SubscriptionItem item = subscription.getItems() == null
                || subscription.getItems().getData() == null
                || subscription.getItems().getData().isEmpty()
                ? null
                : subscription.getItems().getData().get(0);

        return new SubscriptionSnapshot(
                subscription.getId(),
                subscription.getStatus(),
                item == null || item.getPrice() == null ? null : item.getPrice().getId(),
                Boolean.TRUE.equals(subscription.getCancelAtPeriodEnd()),
                // The period is on the item, not the subscription. Stripe moved it.
                item == null ? null : instant(item.getCurrentPeriodStart()),
                item == null ? null : instant(item.getCurrentPeriodEnd()),
                instant(subscription.getCanceledAt()),
                instant(subscription.getTrialEnd()));
    }

    private static Instant instant(Long epochSeconds) {
        return epochSeconds == null ? null : Instant.ofEpochSecond(epochSeconds);
    }
}
