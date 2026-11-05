package com.confluo.payments.pricing;

import com.confluo.stripe.StripeOperationException;
import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.net.RequestOptions;
import com.stripe.param.PriceCreateParams;
import com.stripe.param.PriceUpdateParams;
import com.stripe.param.ProductCreateParams;
import com.stripe.param.ProductUpdateParams;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Locale;


/**
 * Pushes this platform's catalog into Stripe.
 *
 * <p>One direction only. The catalog is defined here because a tier's entitlements and its fee
 * override mean nothing to Stripe, and what Stripe needs is the much smaller part it bills on: a
 * Product per tier and a Price per currency and period. Nothing is ever read back - the local row
 * is the truth, and {@code stripeProductId} and {@code stripePriceId} are just where the copy
 * ended up.</p>
 *
 * <h2>Idempotency keys, and the shape of them</h2>
 *
 * <p>A key derived from the thing being created, never a random one, because the failure this
 * guards is a reconciliation that half-succeeded: Stripe made the product, the response was lost,
 * and the retry must find that product rather than make a second one with the same name. The
 * product's key is its plan key, which is unique and immutable.</p>
 *
 * <p>A price's key includes every field that defines it, which is also why a price has no update:
 * change the amount and it is a <strong>different</strong> price with a different key, and the
 * right outcome is a new Stripe price rather than Stripe refusing because the key was reused with
 * different parameters.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StripeCatalogReconciler {

    private final StripeClient stripe;

    /**
     * Makes sure Stripe has a product for a tier, and returns its id.
     *
     * <p>Creates one if the tier has no product yet; otherwise updates the name and description of
     * the one it has, since those are what a payer sees on the hosted page and are the only part of
     * a product this platform ever changes.</p>
     *
     * @param plan the tier
     * @return Stripe's {@code prod_} identifier
     * @throws StripeOperationException if Stripe refuses
     */
    public String reconcile(Plan plan) {
        if (plan.getStripeProductId() != null) {
            return update(plan);
        }
        return create(plan);
    }

    private String create(Plan plan) {
        try {
            String id = stripe.products().create(
                    ProductCreateParams.builder()
                            .setName(plan.getName())
                            .setDescription(plan.getDescription())
                            // So that somebody looking at a subscription in Stripe's dashboard can
                            // tell which tier of this platform it is, without this service.
                            .putMetadata("plan_key", plan.getKey())
                            .putMetadata("plan_audience", plan.getAudience().name())
                            .build(),
                    RequestOptions.builder()
                            .setIdempotencyKey("plan:" + plan.getKey())
                            .build()).getId();
            log.info("Stripe product created for plan {} [product={}]", plan.getKey(), id);
            return id;
        } catch (StripeException e) {
            throw new StripeOperationException(
                    "Could not create a Stripe product for plan %s".formatted(plan.getKey()), e);
        }
    }

    private String update(Plan plan) {
        try {
            stripe.products().update(plan.getStripeProductId(),
                    ProductUpdateParams.builder()
                            .setName(plan.getName())
                            .setDescription(plan.getDescription())
                            .putMetadata("plan_key", plan.getKey())
                            .putMetadata("plan_audience", plan.getAudience().name())
                            .build());
            log.debug("Stripe product updated for plan {} [product={}]",
                    plan.getKey(), plan.getStripeProductId());
            return plan.getStripeProductId();
        } catch (StripeException e) {
            throw new StripeOperationException(
                    "Could not update Stripe product %s for plan %s"
                            .formatted(plan.getStripeProductId(), plan.getKey()), e);
        }
    }

    /**
     * Creates the Stripe price for a local one, and returns its id.
     *
     * <p>The price's plan must already have a product. That is the caller's business to ensure, and
     * it is checked rather than assumed because the alternative - a Stripe price with no product -
     * is not something Stripe will tell you about in a way that names the tier.</p>
     *
     * @param price the local price, with its plan loaded
     * @return Stripe's {@code price_} identifier
     * @throws IllegalStateException if the tier has not been reconciled yet
     * @throws StripeOperationException if Stripe refuses
     */
    public String reconcile(PlanPrice price) {
        Plan plan = price.getPlan();
        if (plan.getStripeProductId() == null) {
            throw new IllegalStateException(
                    "Plan %s has no Stripe product yet, so it cannot be priced".formatted(plan.getKey()));
        }

        try {
            String id = stripe.prices().create(
                    PriceCreateParams.builder()
                            .setProduct(plan.getStripeProductId())
                            // Stripe speaks lowercase; the column is uppercase, and this is the
                            // boundary where the fold happens.
                            .setCurrency(price.getCurrency().toLowerCase(Locale.ROOT))
                            .setUnitAmount(price.getUnitAmount())
                            .setRecurring(PriceCreateParams.Recurring.builder()
                                    .setInterval(intervalOf(price.getBillingInterval()))
                                    .setIntervalCount((long) price.getIntervalCount())
                                    .build())
                            .putMetadata("plan_key", plan.getKey())
                            .build(),
                    RequestOptions.builder()
                            .setIdempotencyKey(idempotencyKeyFor(price))
                            .build()).getId();
            log.info("Stripe price created for plan {} [price={}, {} {} per {} {}]",
                    plan.getKey(), id, price.getUnitAmount(), price.getCurrency(),
                    price.getIntervalCount(), price.getBillingInterval());
            return id;
        } catch (StripeException e) {
            throw new StripeOperationException(
                    "Could not create a Stripe price for plan %s".formatted(plan.getKey()), e);
        }
    }

    /**
     * Stops Stripe offering a price.
     *
     * <p>Archived, not deleted: Stripe does not allow deleting a price, and should not - the
     * subscriptions already on it are still billed at it. Archiving only stops new ones.</p>
     *
     * <p>A failure here is logged and swallowed. The local row is already inactive, which is what
     * this service's own checkout reads, so the consequence is a price Stripe would still accept
     * through a link nothing in this platform hands out. Failing the caller instead would leave
     * them unable to change a tier's price because of a stale copy.</p>
     *
     * @param stripePriceId Stripe's {@code price_} identifier
     */
    public void archive(String stripePriceId) {
        if (stripePriceId == null) {
            return;
        }
        try {
            stripe.prices().update(stripePriceId,
                    PriceUpdateParams.builder().setActive(false).build());
            log.info("Stripe price {} archived", stripePriceId);
        } catch (StripeException e) {
            log.warn("Could not archive Stripe price {}; it is inactive here regardless: {}",
                    stripePriceId, e.getMessage());
        }
    }

    /**
     * Stops Stripe offering a product.
     *
     * <p>Swallows its failure for the same reason {@link #archive(String)} does: the tier is gone
     * from this platform either way, and this service is the only thing that sells it.</p>
     *
     * @param stripeProductId Stripe's {@code prod_} identifier
     */
    public void archiveProduct(String stripeProductId) {
        if (stripeProductId == null) {
            return;
        }
        try {
            stripe.products().update(stripeProductId,
                    ProductUpdateParams.builder().setActive(false).build());
            log.info("Stripe product {} archived", stripeProductId);
        } catch (StripeException e) {
            log.warn("Could not archive Stripe product {}; it is deleted here regardless: {}",
                    stripeProductId, e.getMessage());
        }
    }

    /**
     * The key for one price.
     *
     * <p>Every field that defines the price is in it, so that re-running a reconciliation reuses
     * Stripe's price and changing any field asks for a new one - which is the only correct
     * behaviour for an object Stripe will not let anybody edit.</p>
     *
     * @param price the local price
     * @return the key
     */
    private String idempotencyKeyFor(PlanPrice price) {
        return "price:%s:%s:%d:%s:%d".formatted(
                price.getPlan().getKey(),
                price.getCurrency(),
                price.getUnitAmount(),
                price.getBillingInterval(),
                price.getIntervalCount());
    }

    private PriceCreateParams.Recurring.Interval intervalOf(BillingInterval interval) {
        return switch (interval) {
            case MONTH -> PriceCreateParams.Recurring.Interval.MONTH;
            case YEAR -> PriceCreateParams.Recurring.Interval.YEAR;
        };
    }
}
