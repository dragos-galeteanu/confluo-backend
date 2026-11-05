package com.confluo.payments.billing.subscription;

import com.confluo.amqp.event.payments.SubscriptionEndedEvent;
import com.confluo.amqp.event.payments.SubscriptionPastDueEvent;
import com.confluo.amqp.event.payments.SubscriptionStartedEvent;
import com.confluo.amqp.publish.EventPublisher;
import com.confluo.payments.pricing.PlanPrice;
import com.confluo.payments.pricing.PlanPriceRepository;
import com.confluo.payments.billing.discount.Discount;
import com.confluo.payments.billing.discount.DiscountRedemption;
import com.confluo.payments.billing.discount.DiscountRedemptionRepository;
import com.confluo.payments.billing.discount.DiscountRepository;
import com.confluo.payments.customer.StripeCustomerRepository;
import com.confluo.webhook.StripeEvent;
import com.confluo.webhook.StripeEventHandler;
import com.stripe.model.Event;
import com.stripe.model.StripeObject;
import com.stripe.model.SubscriptionItem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;


/**
 * What the subscription handlers share: turning Stripe's subscription into this service's row.
 *
 * <p>An upsert, because {@code customer.subscription.created} and
 * {@code customer.subscription.updated} say almost the same thing and Stripe sends both for a new
 * subscription. Treating creation as special would mean the second event had nothing to update,
 * or worse, created a duplicate.</p>
 *
 * <h2>Two things Stripe moved, which this is written against</h2>
 *
 * <p><strong>The billing period is on the items, not the subscription.</strong>
 * {@code current_period_start} and {@code current_period_end} were removed from the subscription
 * object; they now live on each {@link SubscriptionItem}. A subscription sold here has exactly one
 * item, so the first one's period is the subscription's - and reading it off the subscription, as
 * every older example does, silently yields nothing.</p>
 *
 * <p><strong>Discounts are a list.</strong> Not read here: which discount applies is already known
 * from the session's metadata, and that is this platform's own record rather than a reading of
 * Stripe's.</p>
 */
@RequiredArgsConstructor
@Slf4j
public abstract class SubscriptionMirroringHandler implements StripeEventHandler {

    protected final SubscriptionRepository subscriptions;
    protected final PlanPriceRepository prices;
    protected final StripeCustomerRepository customers;
    protected final DiscountRepository discounts;
    protected final DiscountRedemptionRepository redemptions;

    /** Where a change of entitlement is announced. See the payment base for why from here. */
    protected final EventPublisher events;

    /**
     * Writes Stripe's subscription into this service's row, creating it if it is new.
     *
     * @param ledgerRow the event being applied
     * @param stripeSubscription the subscription as Stripe states it
     * @return the row, or empty if this event is older than what the row already holds
     */
    protected Optional<Subscription> mirror(
            StripeEvent ledgerRow, com.stripe.model.Subscription stripeSubscription) {

        Optional<Subscription> existing =
                subscriptions.findByStripeSubscriptionId(stripeSubscription.getId());

        if (existing.isPresent() && isStale(ledgerRow, existing.get().getLastStripeEventAt())) {
            log.info("Ignoring Stripe event {} for subscription {}: it occurred at {}, and {} has "
                            + "already been applied",
                    ledgerRow.getStripeEventId(), stripeSubscription.getId(),
                    ledgerRow.getEventCreatedAt(), existing.get().getLastStripeEventAt());
            return Optional.empty();
        }

        Subscription subscription = existing.orElseGet(Subscription::new);
        boolean isNew = existing.isEmpty();
        // Read before anything below mutates it. `existing.get()` and `subscription` are the same
        // object, so capturing this after the setters would read the new status as the old one -
        // and every transition would look like no change at all.
        SubscriptionStatus before = isNew ? null : subscription.getStatus();

        if (isNew) {
            subscription.setStripeSubscriptionId(stripeSubscription.getId());
            String firebaseUid = firebaseUidOf(stripeSubscription);
            if (firebaseUid == null) {
                throw new IllegalStateException(
                        "Stripe subscription %s names nobody this service knows: it carries no "
                                .formatted(stripeSubscription.getId())
                                + "firebase_uid and its customer is not mapped here");
            }
            subscription.setFirebaseUid(firebaseUid);

            PlanPrice price = priceOf(stripeSubscription);
            if (price == null) {
                throw new IllegalStateException(
                        "Stripe subscription %s is billed at a price this service does not know"
                                .formatted(stripeSubscription.getId()));
            }
            subscription.setPlanPriceId(price.getId());
        }

        subscription.setStatus(statusOf(stripeSubscription.getStatus()));
        subscription.setCancelAtPeriodEnd(
                Boolean.TRUE.equals(stripeSubscription.getCancelAtPeriodEnd()));
        subscription.setCanceledAt(instant(stripeSubscription.getCanceledAt()));
        subscription.setTrialEnd(instant(stripeSubscription.getTrialEnd()));
        applyPeriod(subscription, stripeSubscription);
        subscription.setLastStripeEventAt(ledgerRow.getEventCreatedAt());

        Subscription saved = subscriptions.save(subscription);

        if (isNew) {
            recordRedemption(stripeSubscription, saved);
        }
        announce(saved, before);
        return Optional.of(saved);
    }

    /**
     * Announces a change of entitlement, if the change was one.
     *
     * <p>Three transitions matter and the rest do not. Becoming <strong>live</strong> grants what
     * the plan promises; becoming <strong>past due</strong> puts it at risk; <strong>ending</strong>
     * takes it away. A renewal that leaves a subscription active says nothing new about
     * entitlement - the invoice events cover the money - so nothing is published for it.</p>
     *
     * <p>Trialing counts as live, deliberately: a trial grants everything the plan grants, and a
     * consumer waiting for {@code active} would withhold a product somebody has been told they
     * have.</p>
     *
     * <p>{@code cancelAtPeriodEnd} is <strong>not</strong> an ending. That subscription is still
     * live and still paid for until the period closes, and announcing it here would revoke
     * something somebody has paid for.</p>
     *
     * @param subscription the subscription as it now stands
     * @param before the status it had before, or null if this is the first time it has been seen
     */
    private void announce(Subscription subscription, SubscriptionStatus before) {
        if (subscription.getStatus() == before) {
            return;
        }

        // Read back from pricing by id. A handler runs inside the sweep's transaction, so a lazy
        // association would have worked here - but it is the same read as everywhere else, and one
        // rule about the catalog is worth more than a shortcut that holds in one package.
        PlanPrice price = prices.findByIdWithPlan(subscription.getPlanPriceId())
                .orElseThrow(() -> new IllegalStateException(
                        "Subscription %s is billed at price %s, which the catalog does not have"
                                .formatted(subscription.getId(), subscription.getPlanPriceId())));
        String planKey = price.getPlan().getKey();
        String audience = price.getPlan().getAudience().name();

        if (isLive(subscription.getStatus()) && !isLive(before)) {
            UUID eventId = events.publish(subscription.getId(), new SubscriptionStartedEvent(
                    subscription.getId(),
                    subscription.getFirebaseUid(),
                    planKey,
                    audience,
                    subscription.getStatus() == SubscriptionStatus.TRIALING,
                    subscription.getCurrentPeriodEnd()));
            log.info("Announced subscription.started [subscription={}, event={}]",
                    subscription.getId(), eventId);
            return;
        }

        if (subscription.getStatus() == SubscriptionStatus.PAST_DUE) {
            UUID eventId = events.publish(subscription.getId(), new SubscriptionPastDueEvent(
                    subscription.getId(),
                    subscription.getFirebaseUid(),
                    planKey,
                    audience,
                    subscription.getCurrentPeriodEnd()));
            log.info("Announced subscription.past_due [subscription={}, event={}]",
                    subscription.getId(), eventId);
            return;
        }

        if (isEnded(subscription.getStatus()) && !isEnded(before)) {
            UUID eventId = events.publish(subscription.getId(), new SubscriptionEndedEvent(
                    subscription.getId(),
                    subscription.getFirebaseUid(),
                    planKey,
                    audience,
                    subscription.getStatus() == SubscriptionStatus.UNPAID));
            log.info("Announced subscription.ended [subscription={}, event={}]",
                    subscription.getId(), eventId);
        }
    }

    /** @param status a status, or null @return whether it grants what the plan promises */
    private static boolean isLive(SubscriptionStatus status) {
        return status == SubscriptionStatus.TRIALING || status == SubscriptionStatus.ACTIVE;
    }

    /**
     * Whether a status means the subscription is over.
     *
     * <p>{@code UNPAID} is included: Stripe leaves a subscription there when its retries run out,
     * and although it is not called cancelled, nothing is entitled under it and nothing further
     * will happen to it. Treating it as still running would serve a paid product to somebody who
     * has stopped paying.</p>
     *
     * @param status a status, or null
     * @return whether it is over
     */
    private static boolean isEnded(SubscriptionStatus status) {
        return status == SubscriptionStatus.CANCELED
                || status == SubscriptionStatus.UNPAID
                || status == SubscriptionStatus.INCOMPLETE_EXPIRED;
    }

    /**
     * Copies the billing period off the subscription's first item.
     *
     * <p>See the class javadoc for why the item and not the subscription. A subscription with no
     * items would be one Stripe should not have made; it is tolerated rather than thrown for,
     * because the status is the part that matters and losing the period is not worth refusing an
     * event over.</p>
     *
     * @param subscription the row being written
     * @param stripeSubscription what Stripe sent
     */
    private void applyPeriod(
            Subscription subscription, com.stripe.model.Subscription stripeSubscription) {
        if (stripeSubscription.getItems() == null
                || stripeSubscription.getItems().getData() == null
                || stripeSubscription.getItems().getData().isEmpty()) {
            log.warn("Stripe subscription {} has no items, so it has no billing period",
                    stripeSubscription.getId());
            return;
        }
        SubscriptionItem item = stripeSubscription.getItems().getData().get(0);
        subscription.setCurrentPeriodStart(instant(item.getCurrentPeriodStart()));
        subscription.setCurrentPeriodEnd(instant(item.getCurrentPeriodEnd()));
    }

    /**
     * Records that a discount was used, the first time a subscription is seen.
     *
     * <p>This is where {@code DiscountRedemption} rows finally come from, and therefore where the
     * per-person limit starts meaning anything - before this, every limit would pass forever
     * because nothing had ever been recorded as redeemed.</p>
     *
     * <p>The discount is read from the metadata this platform put on the session rather than from
     * Stripe's own discount list, because the metadata names <em>this service's</em> discount: the
     * coupon is a copy, and the row is the thing the limit is counted against.</p>
     *
     * @param stripeSubscription what Stripe sent
     * @param subscription the row just written
     */
    private void recordRedemption(
            com.stripe.model.Subscription stripeSubscription, Subscription subscription) {

        String discountId = metadata(stripeSubscription, "discount_id");
        if (discountId == null) {
            return;
        }

        UUID id;
        try {
            id = UUID.fromString(discountId);
        } catch (IllegalArgumentException e) {
            log.warn("Stripe subscription {} carries a discount_id that is not an id: {}",
                    stripeSubscription.getId(), discountId);
            return;
        }

        if (redemptions.existsByDiscountIdAndSubscriptionId(id, subscription.getId())) {
            return;
        }

        Optional<Discount> discount = discounts.findById(id);
        if (discount.isEmpty()) {
            log.warn("Stripe subscription {} names discount {}, which this service does not have",
                    stripeSubscription.getId(), id);
            return;
        }

        subscription.setDiscount(discount.get());
        subscriptions.save(subscription);

        DiscountRedemption redemption = new DiscountRedemption();
        redemption.setDiscount(discount.get());
        redemption.setFirebaseUid(subscription.getFirebaseUid());
        redemption.setSubscription(subscription);
        // Stripe's own timestamp for the subscription, not now(): a redemption happened when the
        // subscription was created, which can be well before this event was applied.
        redemption.setRedeemedAt(instant(stripeSubscription.getCreated()) == null
                ? Instant.now()
                : instant(stripeSubscription.getCreated()));
        redemptions.save(redemption);

        log.info("Discount redeemed [discount={}, code={}, subscription={}, uid={}]",
                id, discount.get().getCode(), subscription.getId(), subscription.getFirebaseUid());
    }

    /**
     * Who the subscription belongs to.
     *
     * <p>The metadata first, because this platform put it there. The Stripe customer second, which
     * is what catches a subscription created in Stripe's dashboard - it has no metadata, and
     * refusing it would mean a paying subscriber this service never knew about.</p>
     *
     * @param stripeSubscription what Stripe sent
     * @return the uid, or null if neither says
     */
    private String firebaseUidOf(com.stripe.model.Subscription stripeSubscription) {
        String fromMetadata = metadata(stripeSubscription, "firebase_uid");
        if (fromMetadata != null) {
            return fromMetadata;
        }
        String customerId = stripeSubscription.getCustomer();
        if (customerId == null) {
            return null;
        }
        return customers.findByStripeCustomerId(customerId)
                .map(com.confluo.payments.customer.StripeCustomer::getFirebaseUid)
                .orElse(null);
    }

    /**
     * Which local price the subscription is billed at.
     *
     * <p>The Stripe price on the item first, because that is what Stripe is actually charging - the
     * metadata is what this service <em>intended</em>, and if the two disagree the charge wins.</p>
     *
     * @param stripeSubscription what Stripe sent
     * @return the price, or null if neither route finds one
     */
    private PlanPrice priceOf(com.stripe.model.Subscription stripeSubscription) {
        if (stripeSubscription.getItems() != null
                && stripeSubscription.getItems().getData() != null
                && !stripeSubscription.getItems().getData().isEmpty()) {
            SubscriptionItem item = stripeSubscription.getItems().getData().get(0);
            if (item.getPrice() != null && item.getPrice().getId() != null) {
                Optional<PlanPrice> byStripePrice = prices.findByStripePriceId(item.getPrice().getId());
                if (byStripePrice.isPresent()) {
                    return byStripePrice.get();
                }
                log.warn("Stripe subscription {} is billed at price {}, which this service does "
                                + "not know; falling back to its metadata",
                        stripeSubscription.getId(), item.getPrice().getId());
            }
        }

        String fromMetadata = metadata(stripeSubscription, "plan_price_id");
        if (fromMetadata == null) {
            return null;
        }
        try {
            return prices.findById(UUID.fromString(fromMetadata)).orElse(null);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Stripe's subscription status as this service's own.
     *
     * <p>A direct mapping, because the enum was written to match Stripe's set exactly - which is
     * also why an unrecognised value throws rather than guessing. A status this service does not
     * know could mean anything, and the two plausible defaults are "entitled" and "not entitled":
     * one gives a paid product away and the other takes it from somebody paying. Failing the event
     * leaves the row as it was and puts the decision in front of a person.</p>
     *
     * @param status Stripe's own value
     * @return the mapped status
     */
    private SubscriptionStatus statusOf(String status) {
        if (status == null) {
            throw new IllegalStateException("A Stripe subscription with no status cannot be applied");
        }
        try {
            return SubscriptionStatus.valueOf(status.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "Stripe reported subscription status '%s', which this service does not know; "
                            .formatted(status)
                            + "it is left unapplied rather than guessed at");
        }
    }

    /**
     * Whether an event is older than what has already been applied to a row.
     *
     * <p>The same rule as {@code PaymentMirroringHandler.isStale}, repeated rather than shared
     * because the two hierarchies have nothing else in common - a payment handler's other helpers
     * are all about finding a payment.</p>
     *
     * @param ledgerRow the event being applied
     * @param lastApplied when the newest event already applied occurred, or null
     * @return true if this event should be left alone
     */
    protected boolean isStale(StripeEvent ledgerRow, Instant lastApplied) {
        return lastApplied != null
                && ledgerRow.getEventCreatedAt() != null
                && !ledgerRow.getEventCreatedAt().isAfter(lastApplied);
    }

    /**
     * The subscription an event is about.
     * @param event the event
     * @return Stripe's subscription
     */
    protected com.stripe.model.Subscription subscriptionOf(Event event) {
        Optional<StripeObject> deserialized = event.getDataObjectDeserializer().getObject();
        StripeObject object = deserialized.orElseGet(() -> {
            log.warn("Stripe event {} is at API version {} but this service pins {}; "
                            + "deserialising it anyway",
                    event.getId(), event.getApiVersion(), com.stripe.Stripe.API_VERSION);
            try {
                return event.getDataObjectDeserializer().deserializeUnsafe();
            } catch (Exception e) {
                throw new IllegalStateException(
                        "Could not read the data object of Stripe event %s".formatted(event.getId()), e);
            }
        });
        if (!(object instanceof com.stripe.model.Subscription subscription)) {
            throw new IllegalStateException(
                    "Stripe event %s (%s) carries a %s, not a Subscription".formatted(
                            event.getId(), event.getType(), object.getClass().getSimpleName()));
        }
        return subscription;
    }

    private static Instant instant(Long epochSeconds) {
        return epochSeconds == null ? null : Instant.ofEpochSecond(epochSeconds);
    }

    private static String metadata(com.stripe.model.Subscription subscription, String key) {
        return subscription.getMetadata() == null ? null : subscription.getMetadata().get(key);
    }

}
