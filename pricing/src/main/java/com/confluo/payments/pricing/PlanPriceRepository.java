package com.confluo.payments.pricing;

import com.confluo.common.jpa.repository.BaseJpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;


/**
 * The prices of a plan.
 *
 * <p>Every lookup here is on the active row, because an inactive price is history: it exists for
 * the subscriptions still on it and is never sold again.</p>
 */
@Repository
public interface PlanPriceRepository extends BaseJpaRepository<PlanPrice> {

    /**
     * The price a new subscription to this plan would be sold at.
     *
     * <p>Matches the unique partial index, so there is at most one row to find - which is the whole
     * reason that index exists.</p>
     *
     * @param planId the plan
     * @param currency the currency, uppercase
     * @param billingInterval month or year
     * @param intervalCount how many of those one charge covers
     * @return the active price, or empty if the plan is not sold on those terms
     */
    Optional<PlanPrice> findByPlanIdAndCurrencyAndBillingIntervalAndIntervalCountAndActiveTrue(
            UUID planId, String currency, BillingInterval billingInterval, int intervalCount);

    /**
     * Every price a plan is currently sold at, which is what a tier's listing shows.
     * @param planId the plan
     * @return its active prices
     */
    List<PlanPrice> findByPlanIdAndActiveTrue(UUID planId);

    /**
     * The price a Stripe object refers to, for the webhook that has only Stripe's id.
     * @param stripePriceId Stripe's {@code price_} identifier
     * @return the price, or empty
     */
    Optional<PlanPrice> findByStripePriceId(String stripePriceId);

    /**
     * One price with its tier already loaded.
     *
     * <p>This is how anything outside pricing reads a price. A subscription holds a price's id and
     * not the price, so the caller arrives with a {@link UUID} and generally wants the plan's key
     * or audience as well - and it wants them <strong>after</strong> the transaction that fetched
     * them has closed, because the services that ask call Stripe and deliberately hold no
     * transaction across a network round trip. A lazy walk to {@code plan} there is a proxy with
     * no session, which throws.</p>
     *
     * <p>No {@code active} filter, unlike everything above: a subscription on a withdrawn price
     * still has to be readable. An inactive price is history, and this is the lookup that reads
     * history.</p>
     *
     * @param id the price
     * @return the price with its plan loaded, or empty
     */
    @Query("select price from PlanPrice price join fetch price.plan where price.id = :id")
    Optional<PlanPrice> findByIdWithPlan(@Param("id") UUID id);

    /**
     * Several prices with their tiers loaded, for a caller holding a list of ids.
     *
     * <p>One query for a page of subscriptions. The alternative is {@link #findByIdWithPlan} per
     * row, which is the N+1 that a fetch join on the subscription side used to hide.</p>
     *
     * @param ids the prices
     * @return the matching prices, each with its plan loaded
     */
    @Query("select price from PlanPrice price join fetch price.plan where price.id in :ids")
    List<PlanPrice> findByIdInWithPlan(@Param("ids") Collection<UUID> ids);
}
