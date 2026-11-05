package com.confluo.payments.pricing;

import com.confluo.common.exception.ResourceNotFoundException;
import com.confluo.payments.pricing.dto.PlanPriceCreateRequest;
import com.confluo.payments.pricing.dto.PlanPriceResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;


/**
 * What the tiers cost.
 *
 * <p>Add-only. A Stripe price cannot be edited, which is not a limitation to work around but the
 * right model: the subscriptions already on a price are billed at it, so changing what a tier costs
 * has to leave the old price readable. Raising a price is therefore adding one and deactivating its
 * predecessor, which is what {@link #add} does.</p>
 *
 * <h2>Stripe first here, unlike everywhere else</h2>
 *
 * <p>{@code OrderCheckoutService} commits its local row before calling Stripe, because a charge
 * Stripe knows about and this service does not is money nobody can account for. A price is the
 * opposite way round: its idempotency key is derived entirely from the request - the plan's key,
 * the currency, the amount and the period - so Stripe can be asked for the price <strong>before</strong>
 * any row exists, and a failure afterwards leaves nothing but an unreferenced Stripe price that
 * this platform never sells and the next attempt reuses.</p>
 *
 * <p>Which buys something worth having: all the local writes - the new row, and the deactivation of
 * the one it replaces - happen in a single short transaction with no network call inside it. The
 * tier can never be left with two active prices for one shape, or none.</p>
 */
@Service
@Slf4j
public class PlanPriceService {

    private final PlanPriceRepository repository;
    private final PlanPriceMapper mapper;

    /**
     * The plans, read directly rather than through {@code PlanService}.
     *
     * <p>All this needs is to fetch one tier, and depending on the service for that would couple
     * two services together for a lookup. It also avoids a subtler trap: {@code PlanService}
     * implements {@code BaseService} and has {@code @Transactional} methods, so Spring can proxy it
     * by interface - in which case the bean is not a {@code PlanService} at all and could not be
     * injected here by its concrete type. Spring Boot happens to default to class proxies, which is
     * why the controllers' own concrete injections work, but relying on that setting for a
     * dependency this one does not need would be a strange thing to do.</p>
     */
    private final PlanRepository plans;
    private final StripeCatalogReconciler reconciler;
    private final TransactionTemplate transaction;

    /**
     * @param repository the prices
     * @param mapper the request and response conversions
     * @param plans the tiers
     * @param reconciler what pushes a price into Stripe
     * @param transactionManager the service's transaction manager
     */
    public PlanPriceService(
            PlanPriceRepository repository,
            PlanPriceMapper mapper,
            PlanRepository plans,
            StripeCatalogReconciler reconciler,
            PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.mapper = mapper;
        this.plans = plans;
        this.reconciler = reconciler;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /**
     * Adds a price to a tier, and retires whatever it replaces.
     *
     * @param planId which tier
     * @param request the price
     * @return the price
     * @throws ResourceNotFoundException if the tier does not exist
     * @throws IllegalStateException if the tier has not been reconciled into Stripe yet, since
     *         there is no product for a price to belong to
     */
    public PlanPriceResponse add(UUID planId, PlanPriceCreateRequest request) {
        Plan plan = plan(planId);
        if (plan.getStripeProductId() == null) {
            throw new IllegalStateException(
                    "Plan %s has not been reconciled into Stripe yet, so it cannot be priced; "
                            .formatted(plan.getKey())
                            + "reconcile it first");
        }

        // Built but not saved: the reconciler needs the shape, and Stripe's price can be made
        // before anything is written here. See the class javadoc.
        PlanPrice price = new PlanPrice();
        mapper.createEntityFromRequest(price, request);
        price.setPlan(plan);
        price.setStripePriceId(reconciler.reconcile(price));

        return transaction.execute(status -> {
            Optional<PlanPrice> replaced = repository
                    .findByPlanIdAndCurrencyAndBillingIntervalAndIntervalCountAndActiveTrue(
                            planId, request.currency(), request.billingInterval(), request.intervalCount());

            // Deactivated before the insert, because uq_plan_prices_active_shape allows only one
            // active row per shape - and flushed, which is the part that is easy to miss.
            // Hibernate orders inserts before updates within a flush, so a plain save here would
            // leave the deactivation queued behind the new row's insert and the index would reject
            // it. saveAndFlush forces the update out first, in statement order.
            replaced.ifPresent(previous -> {
                previous.setActive(false);
                repository.saveAndFlush(previous);
            });

            price.setActive(true);
            PlanPrice saved = repository.save(price);

            // Inside the transaction, so the mapper can read the price's plan. Mapping it outside
            // would find a lazy proxy on a closed session - which is exactly what happened when
            // this method did its saving without one.
            PlanPriceResponse response = mapper.toResponse(saved);

            replaced.ifPresent(previous -> {
                reconciler.archive(previous.getStripePriceId());
                log.info("Plan price retired [id={}, plan={}, {} {}]",
                        previous.getId(), plan.getKey(), previous.getUnitAmount(), previous.getCurrency());
            });

            log.info("Plan price added [id={}, plan={}, {} {} per {} {}, stripePrice={}]",
                    saved.getId(), plan.getKey(), saved.getUnitAmount(), saved.getCurrency(),
                    saved.getIntervalCount(), saved.getBillingInterval(), saved.getStripePriceId());
            return response;
        });
    }

    /**
     * The prices a tier is currently sold at.
     * @param planId which tier
     * @return its active prices
     */
    @Transactional(readOnly = true)
    public List<PlanPriceResponse> getActive(UUID planId) {
        return repository.findByPlanIdAndActiveTrue(planId).stream()
                .map(mapper::toResponse)
                .toList();
    }

    /**
     * Stops selling at a price without replacing it.
     *
     * <p>For withdrawing a currency or a billing period, which is different from changing what a
     * tier costs. The subscriptions on it keep being billed at it: Stripe bills a subscription from
     * the price it was created with, and archiving one only stops new subscriptions.</p>
     *
     * @param priceId which price
     * @return the price
     */
    public PlanPriceResponse deactivate(UUID priceId) {
        Deactivation outcome = transaction.execute(status -> {
            PlanPrice price = repository.findById(priceId)
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Plan price %s not found".formatted(priceId)));

            boolean changed = price.isActive();
            if (changed) {
                price.setActive(false);
                price = repository.save(price);
            }
            // Mapped in here for the same reason as in add: the response names the price's plan.
            return new Deactivation(mapper.toResponse(price), price.getStripePriceId(),
                    price.getPlan().getKey(), changed);
        });

        if (outcome.changed()) {
            reconciler.archive(outcome.stripePriceId());
            log.info("Plan price deactivated [id={}, plan={}]", priceId, outcome.planKey());
        }
        return outcome.response();
    }

    private Plan plan(UUID planId) {
        return plans.findById(planId)
                .orElseThrow(() -> new ResourceNotFoundException("Plan %s not found".formatted(planId)));
    }

    /**
     * What a deactivation needs to carry out of its transaction.
     *
     * <p>The Stripe call is deliberately outside it, so the two things it needs - the id to archive
     * and the plan's key for the log - have to be read while the session is still open and carried
     * out rather than fetched after.</p>
     *
     * @param response the price as the API returns it
     * @param stripePriceId what to archive at Stripe
     * @param planKey the tier's name, for the log
     * @param changed whether anything actually changed
     */
    private record Deactivation(
            PlanPriceResponse response, String stripePriceId, String planKey, boolean changed) {
    }
}
