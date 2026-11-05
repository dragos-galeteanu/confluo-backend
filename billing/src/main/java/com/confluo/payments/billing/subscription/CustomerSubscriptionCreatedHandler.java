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
 * Somebody has subscribed.
 *
 * <p>The event that brings a subscription into existence here. Nothing is written when the checkout
 * session is created - a subscription's identifier is Stripe's and cannot be invented in advance -
 * so this is the first moment the row can exist, built from the metadata the session carried.</p>
 *
 * <p>It is also where a discount's redemption is recorded, which is what makes a per-person
 * redemption limit mean anything at all.</p>
 */
@Component
@Slf4j
public class CustomerSubscriptionCreatedHandler extends SubscriptionMirroringHandler {

    /**
     * @param subscriptions the mirrored subscriptions
     * @param prices the plan prices, to resolve what is being billed
     * @param customers the identity mapping, for a subscription made outside this service
     * @param discounts the discounts, to attach the one that was used
     * @param redemptions where a use of a discount is recorded
     * @param events where a change of entitlement is announced
     */
    public CustomerSubscriptionCreatedHandler(
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
        return "customer.subscription.created";
    }

    @Override
    public void handle(StripeEvent ledgerRow, Event event) {
        com.stripe.model.Subscription stripeSubscription = subscriptionOf(event);
        mirror(ledgerRow, stripeSubscription).ifPresent(subscription ->
                log.info("Subscription created [id={}, uid={}, status={}, stripeSubscription={}]",
                        subscription.getId(), subscription.getFirebaseUid(),
                        subscription.getStatus(), stripeSubscription.getId()));
    }
}
