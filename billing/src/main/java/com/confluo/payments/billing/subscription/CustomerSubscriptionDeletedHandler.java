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
 * A subscription ended.
 *
 * <p>Stripe calls this {@code deleted}; nothing is deleted here. The row stays, with a status of
 * {@link SubscriptionStatus#CANCELED} and the time it ended - because "what were they paying for
 * until March" is a question asked long after somebody has left, and a deleted row cannot answer
 * it.</p>
 *
 * <p>The ordinary end of a subscription somebody cancelled, and also how one that ran out of
 * collection attempts finishes. {@code cancelAtPeriodEnd} being true is <em>not</em> this: that is
 * a subscription still live and still paid for, which arrives as an update.</p>
 */
@Component
@Slf4j
public class CustomerSubscriptionDeletedHandler extends SubscriptionMirroringHandler {

    /**
     * @param subscriptions the mirrored subscriptions
     * @param prices the plan prices
     * @param customers the identity mapping
     * @param discounts the discounts
     * @param redemptions where a use of a discount is recorded
     * @param events where a change of entitlement is announced
     */
    public CustomerSubscriptionDeletedHandler(
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
        return "customer.subscription.deleted";
    }

    @Override
    public void handle(StripeEvent ledgerRow, Event event) {
        com.stripe.model.Subscription stripeSubscription = subscriptionOf(event);
        mirror(ledgerRow, stripeSubscription).ifPresent(subscription ->
                log.info("Subscription ended [id={}, uid={}, status={}, canceledAt={}]",
                        subscription.getId(), subscription.getFirebaseUid(),
                        subscription.getStatus(), subscription.getCanceledAt()));
    }
}
