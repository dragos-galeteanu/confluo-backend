package com.confluo.payments.billing.subscription;

import com.confluo.amqp.publish.EventPublisher;
import com.confluo.payments.pricing.PlanPriceRepository;
import com.confluo.payments.billing.discount.DiscountRedemptionRepository;
import com.confluo.payments.billing.discount.DiscountRepository;
import com.confluo.payments.customer.StripeCustomerRepository;
import com.confluo.webhook.StripeEvent;
import com.stripe.model.Event;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * A subscription changed.
 *
 * <p>The workhorse of the three, and the one that carries the facts anybody acts on: a renewal
 * moving the period forward, a failed collection moving the status to {@code past_due}, a
 * cancellation scheduled for the period end, a trial finishing. Every one of those arrives here.</p>
 *
 * <p>An upsert rather than an update, deliberately. Stripe will send this for a subscription this
 * service has never seen - one created in the dashboard, or one whose {@code created} event was
 * lost - and treating that as an error would leave a paying subscriber unrecorded. See
 * {@link SubscriptionMirroringHandler} for how the row is built when there is nothing to update.</p>
 */
@Component
@Slf4j
public class CustomerSubscriptionUpdatedHandler extends SubscriptionMirroringHandler {

    /**
     * @param subscriptions the mirrored subscriptions
     * @param prices the plan prices
     * @param customers the identity mapping
     * @param discounts the discounts
     * @param redemptions where a use of a discount is recorded
     * @param events where a change of entitlement is announced
     */
    public CustomerSubscriptionUpdatedHandler(
            SubscriptionRepository subscriptions,
            PlanPriceRepository prices,
            StripeCustomerRepository customers,
            DiscountRepository discounts,
            DiscountRedemptionRepository redemptions,
            EventPublisher events) {
        super(subscriptions, prices, customers, discounts, redemptions, events);
    }

    @Override
    public String eventType() {
        return "customer.subscription.updated";
    }

    @Override
    public void handle(StripeEvent ledgerRow, Event event) {
        com.stripe.model.Subscription stripeSubscription = subscriptionOf(event);
        mirror(ledgerRow, stripeSubscription).ifPresent(subscription ->
                log.info("Subscription updated [id={}, uid={}, status={}, periodEnd={}, "
                                + "cancelAtPeriodEnd={}]",
                        subscription.getId(), subscription.getFirebaseUid(), subscription.getStatus(),
                        subscription.getCurrentPeriodEnd(), subscription.isCancelAtPeriodEnd()));
    }
}
