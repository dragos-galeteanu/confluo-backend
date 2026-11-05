package com.confluo.payments.billing.subscription;

import com.confluo.common.exception.ResourceNotFoundException;
import com.confluo.payments.pricing.Plan;
import com.confluo.payments.pricing.PlanPrice;
import com.confluo.payments.pricing.PlanPriceRepository;
import com.confluo.payments.pricing.PlanRepository;
import com.confluo.payments.billing.subscription.dto.SubscriptionChangeRequest;
import com.confluo.payments.billing.subscription.dto.SubscriptionResponse;
import com.confluo.stripe.SubscriptionGateway;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;


/**
 * Changing and ending a subscription somebody already has.
 *
 * <h2>Why nothing here writes the local row</h2>
 *
 * <p>Every method calls Stripe and then returns Stripe's answer, leaving {@code subscriptions}
 * untouched. The {@code customer.subscription.*} webhook is the only writer of mirrored state, and
 * that is not tidiness - it is load-bearing.</p>
 *
 * <p>{@code SubscriptionMirroringHandler} announces a change of entitlement by comparing the status
 * before and after. If this service wrote the new status first, the webhook would arrive, find
 * nothing had changed, and publish <strong>nothing</strong> - so a cancellation would never
 * announce {@code subscription.ended} and every consumer would go on granting access. The bug
 * would be invisible here: the row would look right.</p>
 *
 * <p>So the division is: this service decides and asks, Stripe answers, the webhook records and
 * announces. A caller gets the truth immediately from the response and the row catches up moments
 * later.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SubscriptionManagementService {

    private final SubscriptionRepository subscriptions;
    private final PlanRepository plans;
    private final PlanPriceRepository prices;
    private final SubscriptionGateway gateway;

    /**
     * Moves a subscription onto a different tier or period, invoicing the difference now.
     *
     * @param subscriptionId which subscription
     * @param request what to move it to
     * @return the subscription as Stripe now has it
     * @throws ResourceNotFoundException if the subscription or the target price does not exist
     * @throws IllegalStateException if the subscription is over, if the target is what it is
     *         already on, or if the tier is on the other side of the marketplace
     */
    public SubscriptionResponse change(UUID subscriptionId, SubscriptionChangeRequest request) {
        Subscription subscription = getEntityById(subscriptionId);
        requireChangeable(subscription);

        PlanPrice current = priceOf(subscription);
        Plan plan = plans.findByKey(request.planKey().toLowerCase(Locale.ROOT))
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Plan %s not found".formatted(request.planKey())));

        if (plan.getDisabledAt() != null || plan.getDeletedAt() != null) {
            throw new IllegalStateException(
                    "Plan %s is no longer offered".formatted(plan.getKey()));
        }

        // Moving a provider onto a customer tier, or the reverse, is not a change of plan - it is
        // a different product sold to a different side of the marketplace, and the fee override
        // that follows a provider tier would start or stop applying silently.
        if (plan.getAudience() != current.getPlan().getAudience()) {
            throw new IllegalStateException(
                    "Plan %s is sold to %s and this subscription is a %s one; that is not a change "
                            .formatted(plan.getKey(), plan.getAudience(),
                                    current.getPlan().getAudience())
                            + "of plan");
        }

        // Stripe will not change a subscription's currency, so the new price has to be in the one
        // it is already billed in. Refused here rather than by Stripe, where the message would be
        // about a parameter rather than about what the caller asked for.
        PlanPrice target = prices
                .findByPlanIdAndCurrencyAndBillingIntervalAndIntervalCountAndActiveTrue(
                        plan.getId(), current.getCurrency(), request.billingInterval(),
                        request.intervalCount())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Plan %s is not sold in %s per %d %s, which is what this subscription is "
                                .formatted(plan.getKey(), current.getCurrency(),
                                        request.intervalCount(), request.billingInterval())
                                + "billed in"));

        if (target.getId().equals(current.getId())) {
            throw new IllegalStateException(
                    "This subscription is already on that price; there is nothing to change");
        }
        if (target.getStripePriceId() == null) {
            throw new IllegalStateException(
                    "The price of plan %s has not been reconciled into Stripe, so nothing can be "
                            .formatted(plan.getKey()) + "moved onto it");
        }

        SubscriptionGateway.SubscriptionSnapshot snapshot = gateway.changePrice(
                subscription.getStripeSubscriptionId(), target.getStripePriceId());

        log.info("Subscription changed [id={}, uid={}, {} -> {}, invoiced immediately]",
                subscription.getId(), subscription.getFirebaseUid(),
                current.getPlan().getKey(), plan.getKey());
        return respond(subscription, target, plan.getKey(), snapshot);
    }

    /**
     * Ends a subscription.
     *
     * @param subscriptionId which subscription
     * @param atPeriodEnd true to let it run to the end of what has been paid for, false to end it
     *                    now and forfeit the remainder
     * @return the subscription as Stripe now has it
     * @throws IllegalStateException if it is already over
     */
    public SubscriptionResponse cancel(UUID subscriptionId, boolean atPeriodEnd) {
        Subscription subscription = getEntityById(subscriptionId);
        requireChangeable(subscription);

        SubscriptionGateway.SubscriptionSnapshot snapshot =
                gateway.cancel(subscription.getStripeSubscriptionId(), atPeriodEnd);

        log.info("Subscription cancelled [id={}, uid={}, atPeriodEnd={}]",
                subscription.getId(), subscription.getFirebaseUid(), atPeriodEnd);
        PlanPrice price = priceOf(subscription);
        return respond(subscription, price, price.getPlan().getKey(), snapshot);
    }

    /**
     * Calls off a cancellation that was scheduled for the period end.
     *
     * @param subscriptionId which subscription
     * @return the subscription as Stripe now has it
     * @throws IllegalStateException if it is over, or if no cancellation was scheduled
     */
    public SubscriptionResponse resume(UUID subscriptionId) {
        Subscription subscription = getEntityById(subscriptionId);
        requireChangeable(subscription);

        if (!subscription.isCancelAtPeriodEnd()) {
            throw new IllegalStateException(
                    "This subscription is not scheduled to stop, so there is nothing to resume");
        }

        SubscriptionGateway.SubscriptionSnapshot snapshot =
                gateway.resume(subscription.getStripeSubscriptionId());

        log.info("Subscription will renew after all [id={}, uid={}]",
                subscription.getId(), subscription.getFirebaseUid());
        PlanPrice price = priceOf(subscription);
        return respond(subscription, price, price.getPlan().getKey(), snapshot);
    }

    /**
     * The subscription with an id.
     *
     * <p>Just the row. It used to be fetched together with its price and tier, because the plan's
     * key was read through a lazy association <strong>after</strong> this transaction had closed -
     * these methods call Stripe, so they deliberately hold none - and a proxy with no session is
     * how that first failed. The subscription now holds a price id, and {@link #priceOf} is where
     * the catalog is read.</p>
     *
     * @param id which subscription
     * @return the subscription
     * @throws ResourceNotFoundException if there is none
     */
    @Transactional(readOnly = true)
    public Subscription getEntityById(UUID id) {
        return subscriptions.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Subscription %s not found".formatted(id)));
    }

    /**
     * The price a subscription is on, with its tier loaded.
     *
     * <p>A separate query to pricing rather than a join from the subscription, which is what
     * holding an id instead of an association costs: one more round trip per subscription read.
     * Bought deliberately - the join it replaces is the one that stops working the day pricing
     * owns its own database, and the fetch join inside pricing means the plan is loaded by the
     * time this returns, so callers outside a transaction can read it.</p>
     *
     * @param subscription the subscription
     * @return the price it is billed at, with its plan
     * @throws IllegalStateException if the catalog has no such price, which the foreign key on
     *         {@code subscriptions.plan_price_id} makes impossible today
     */
    private PlanPrice priceOf(Subscription subscription) {
        return prices.findByIdWithPlan(subscription.getPlanPriceId())
                .orElseThrow(() -> new IllegalStateException(
                        "Subscription %s is billed at price %s, which the catalog does not have"
                                .formatted(subscription.getId(), subscription.getPlanPriceId())));
    }

    /**
     * Everything somebody holds.
     *
     * <p>More than one is normal: both sides of the marketplace subscribe, so a person can be a
     * paying provider and a paying customer at once.</p>
     *
     * @param firebaseUid whose subscriptions
     * @return their subscriptions
     */
    @Transactional(readOnly = true)
    public List<SubscriptionResponse> getForUser(String firebaseUid) {
        List<Subscription> held = subscriptions.findByFirebaseUid(firebaseUid);
        if (held.isEmpty()) {
            return List.of();
        }

        // Two queries for the whole page rather than one per row. Holding a price id instead of an
        // association means the N+1 is no longer hidden by a fetch join - it is here to be seen,
        // and avoided.
        Map<UUID, PlanPrice> catalog = prices
                .findByIdInWithPlan(held.stream().map(Subscription::getPlanPriceId).distinct().toList())
                .stream()
                .collect(Collectors.toMap(PlanPrice::getId, Function.identity()));

        return held.stream()
                .map(subscription -> {
                    PlanPrice price = catalog.get(subscription.getPlanPriceId());
                    if (price == null) {
                        throw new IllegalStateException(
                                "Subscription %s is billed at price %s, which the catalog does not "
                                        .formatted(subscription.getId(), subscription.getPlanPriceId())
                                        + "have");
                    }
                    return respond(subscription, price, price.getPlan().getKey(), null);
                })
                .toList();
    }

    /**
     * One subscription, as the API returns it.
     * @param id which subscription
     * @return the subscription
     */
    @Transactional(readOnly = true)
    public SubscriptionResponse getById(UUID id) {
        Subscription subscription = getEntityById(id);
        PlanPrice price = priceOf(subscription);
        return respond(subscription, price, price.getPlan().getKey(), null);
    }

    /**
     * Refuses a subscription that is over.
     *
     * <p>Stripe refuses it too, and its message is about a subscription id rather than about what
     * somebody tried to do. The states that remain changeable are the live ones plus past due,
     * which is deliberately included: somebody behind on payments who wants to downgrade should be
     * able to, and refusing them is how a lapse becomes a cancellation.</p>
     *
     * @param subscription the subscription
     */
    private void requireChangeable(Subscription subscription) {
        SubscriptionStatus status = subscription.getStatus();
        boolean changeable = status == SubscriptionStatus.TRIALING
                || status == SubscriptionStatus.ACTIVE
                || status == SubscriptionStatus.PAST_DUE;

        if (!changeable) {
            throw new IllegalStateException(
                    "Subscription %s is %s and cannot be changed; a new subscription is the only "
                            .formatted(subscription.getId(), status) + "way back");
        }
    }

    /**
     * Builds the response, preferring Stripe's answer where there is one.
     *
     * <p>{@code snapshot} is null on a plain read, where the stored row is all there is and is
     * current. After a write it is Stripe's own answer, which is newer than the row - the webhook
     * has not arrived yet.</p>
     *
     * <p>{@code planKey} is passed rather than read from {@code price.getPlan()}, deliberately.
     * These methods run outside a transaction, and the price a change moves <em>to</em> comes from
     * a derived query that does not fetch its plan - so walking the association there is a proxy
     * with no session. Every caller already knows the key, so asking for it is both cheaper and
     * impossible to get wrong.</p>
     *
     * @param subscription the stored row
     * @param price the price it is on, or is moving to
     * @param planKey the key of that price's tier
     * @param snapshot Stripe's answer, or null for a read
     * @return the response
     */
    private SubscriptionResponse respond(
            Subscription subscription,
            PlanPrice price,
            String planKey,
            SubscriptionGateway.SubscriptionSnapshot snapshot) {

        SubscriptionStatus status = snapshot == null
                ? subscription.getStatus()
                : statusOf(snapshot.status(), subscription.getStatus());

        return new SubscriptionResponse(
                subscription.getId(),
                subscription.getFirebaseUid(),
                planKey,
                status,
                status == SubscriptionStatus.TRIALING || status == SubscriptionStatus.ACTIVE,
                price.getUnitAmount(),
                price.getCurrency(),
                price.getBillingInterval(),
                price.getIntervalCount(),
                snapshot == null ? subscription.getCurrentPeriodStart() : snapshot.currentPeriodStart(),
                snapshot == null ? subscription.getCurrentPeriodEnd() : snapshot.currentPeriodEnd(),
                snapshot == null ? subscription.isCancelAtPeriodEnd() : snapshot.cancelAtPeriodEnd(),
                snapshot == null ? subscription.getCanceledAt() : snapshot.canceledAt(),
                snapshot == null ? subscription.getTrialEnd() : snapshot.trialEnd());
    }

    /**
     * Stripe's status as this service's own, falling back rather than failing.
     *
     * <p>Unlike the webhook handler, which throws on a status it does not know: there, refusing
     * leaves the row untouched and a person can look at it. Here the change has already been made
     * at Stripe, and failing the response would tell the caller their request did not happen when
     * it did. So the stored status is reported instead and the webhook will correct it.</p>
     *
     * @param status Stripe's own value
     * @param fallback what the row currently says
     * @return the mapped status
     */
    private SubscriptionStatus statusOf(String status, SubscriptionStatus fallback) {
        if (status == null) {
            return fallback;
        }
        try {
            return SubscriptionStatus.valueOf(status.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            log.warn("Stripe reported subscription status '{}', which this service does not know; "
                    + "reporting {} until the webhook settles it", status, fallback);
            return fallback;
        }
    }
}
