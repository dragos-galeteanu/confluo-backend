package com.confluo.payments.billing.discount;

import com.confluo.common.jpa.repository.BaseJpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;


/**
 * Who has used which discount. The per-person limit is answered from here.
 *
 * <p>Counts rather than lists on the two eligibility paths, because the only thing being decided is
 * whether a number has been reached - loading the rows to size a collection is a page of IO for an
 * integer, on a path every checkout takes.</p>
 */
@Repository
public interface DiscountRedemptionRepository extends BaseJpaRepository<DiscountRedemption> {

    /**
     * How many times one person has used a discount, which is the limit Stripe cannot enforce.
     * @param discountId the discount
     * @param firebaseUid the uid
     * @return the number of redemptions
     */
    long countByDiscountIdAndFirebaseUid(UUID discountId, String firebaseUid);

    /**
     * How many times a discount has been used in total.
     *
     * <p>Stripe enforces this one too, on the coupon. Counted here as well so that the answer at
     * checkout is a 422 naming the discount rather than a rejection from Stripe in front of the
     * payer.</p>
     *
     * @param discountId the discount
     * @return the number of redemptions
     */
    long countByDiscountId(UUID discountId);

    /**
     * Whether a discount has already been recorded against a subscription.
     *
     * <p>Keyed by the subscription rather than the person, deliberately: somebody who subscribes,
     * cancels and subscribes again with the same code has redeemed it twice, and that is two rows.
     * What this prevents is one subscription producing two.</p>
     *
     * <p>Belt and braces rather than the guarantee. The webhook ledger is what makes
     * {@code customer.subscription.created} impossible to apply twice; this covers the case where
     * the same subscription is announced by some other event as well.</p>
     *
     * @param discountId the discount
     * @param subscriptionId the subscription
     * @return true if the redemption is already recorded
     */
    boolean existsByDiscountIdAndSubscriptionId(UUID discountId, UUID subscriptionId);

    /**
     * Every use of a discount, for whoever is asking what a campaign cost.
     * @param discountId the discount
     * @return its redemptions, newest first
     */
    List<DiscountRedemption> findByDiscountIdOrderByRedeemedAtDesc(UUID discountId);
}
