package com.confluo.payments.billing.subscription;

import com.confluo.common.jpa.repository.BaseJpaRepository;
import com.confluo.payments.pricing.PlanAudience;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;


/**
 * Subscriptions, by Stripe's id for webhooks and by uid for everything else.
 *
 * <p>The status-filtered lookups take a collection rather than naming the statuses themselves,
 * because which statuses count as entitled is a decision the caller makes -
 * {@link Subscription#isEntitled()} holds the default, and the dunning sweep wants a different
 * set entirely.</p>
 */
@Repository
public interface SubscriptionRepository extends BaseJpaRepository<Subscription> {

    /**
     * The subscription an event is about.
     * @param stripeSubscriptionId Stripe's {@code sub_} identifier
     * @return the subscription, or empty
     */
    Optional<Subscription> findByStripeSubscriptionId(String stripeSubscriptionId);

    /**
     * Everything somebody holds. More than one is normal: both sides of the marketplace subscribe,
     * so a person can be a paying provider and a paying customer at once.
     * @param firebaseUid the uid
     * @return their subscriptions
     */
    List<Subscription> findByFirebaseUid(String firebaseUid);

    /**
     * Somebody's subscriptions in particular states, which is how an entitlement is answered.
     * @param firebaseUid the uid
     * @param statuses the states that count
     * @return the matching subscriptions
     */
    List<Subscription> findByFirebaseUidAndStatusIn(
            String firebaseUid, Collection<SubscriptionStatus> statuses);

    /**
     * The best platform-fee rate a seller's subscriptions entitle them to, in basis points.
     *
     * <p>Written out rather than assembled in Java from
     * {@link #findByFirebaseUidAndStatusIn}: this is one query returning one number, and it cannot
     * be broken by being called outside a transaction - which is how it was broken when it was
     * written the other way.</p>
     *
     * <p>The join to {@code PlanPrice} is written out with an {@code ON} clause, because a
     * subscription holds a price's <strong>id</strong> and not an association to it. That is HQL
     * rather than strict JPQL, and it is also the honest shape: this reads another module's tables,
     * which is a thing to see in the query rather than infer from a mapping. This query and
     * {@link #existsLiveForAudience} are the only two places billing reads pricing's tables
     * itself; everything else goes through {@code PlanPriceRepository}.</p>
     *
     * <p>{@code MIN} because two entitled subscriptions must not make the answer depend on which
     * row came back first - and if somebody is paying for two tiers, the better of the two is the
     * one they bought.</p>
     *
     * @param firebaseUid whose rate it is
     * @param statuses the states that count as entitled
     * @param audience which side of the marketplace the plan must be for, which is
     *                 {@code PROVIDER}: a customer tier cannot change a seller's fee
     * @return the lowest override, or empty if none of their plans carries one
     */
    @Query("""
            SELECT MIN(plan.platformFeeBpsOverride)
            FROM Subscription subscription
            JOIN PlanPrice price ON price.id = subscription.planPriceId
            JOIN price.plan plan
            WHERE subscription.firebaseUid = :firebaseUid
              AND subscription.status IN :statuses
              AND plan.audience = :audience
              AND plan.platformFeeBpsOverride IS NOT NULL
            """)
    Optional<Integer> findLowestPlatformFeeOverride(
            @Param("firebaseUid") String firebaseUid,
            @Param("statuses") Collection<SubscriptionStatus> statuses,
            @Param("audience") PlanAudience audience);

    /**
     * Whether somebody already holds a live subscription to one side of the marketplace.
     *
     * <p>The guard on a new subscription checkout. Both sides subscribe, so a person may hold a
     * provider plan and a customer plan at once - but two provider plans is not a second purchase,
     * it is the same person billed twice for the same thing, which Stripe will happily do.</p>
     *
     * <p>A join rather than a walk in Java, and an {@code ON} clause rather than an association,
     * for the reasons {@link #findLowestPlatformFeeOverride} gives.</p>
     *
     * @param firebaseUid whose subscriptions
     * @param statuses the states that count as live
     * @param audience which side of the marketplace
     * @return true if they already hold one
     */
    @Query("""
            SELECT COUNT(subscription) > 0
            FROM Subscription subscription
            JOIN PlanPrice price ON price.id = subscription.planPriceId
            JOIN price.plan plan
            WHERE subscription.firebaseUid = :firebaseUid
              AND subscription.status IN :statuses
              AND plan.audience = :audience
            """)
    boolean existsLiveForAudience(
            @Param("firebaseUid") String firebaseUid,
            @Param("statuses") Collection<SubscriptionStatus> statuses,
            @Param("audience") PlanAudience audience);

    /**
     * Everything in a given state, which is what the dunning sweep reads.
     *
     * <p>Backed by {@code ix_subscriptions_collection}, which is partial on exactly the two states
     * that sweep asks for - so calling this with any others scans.</p>
     *
     * @param statuses the states to collect, normally PAST_DUE and UNPAID
     * @return the matching subscriptions
     */
    List<Subscription> findByStatusIn(Collection<SubscriptionStatus> statuses);
}
