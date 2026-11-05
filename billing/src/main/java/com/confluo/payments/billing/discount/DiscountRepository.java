package com.confluo.payments.billing.discount;

import com.confluo.common.jpa.repository.BaseJpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;


/**
 * Discounts, by the code a payer types and by Stripe's own identifiers.
 */
@Repository
public interface DiscountRepository extends BaseJpaRepository<Discount> {

    /**
     * The discount a code names.
     *
     * <p>Exact, and the caller uppercases first - the same bargain as the notifications service's
     * lookup by email address. An {@code IgnoreCase} derived method compiles to
     * {@code upper(code) = upper(?)}, which cannot use {@code uq_discounts_code} and so scans the
     * table on a path every checkout takes. {@code ck_discounts_code_uppercase} is what makes
     * exact matching correct rather than merely fast.</p>
     *
     * @param code the code, uppercased
     * @return the discount, or empty if no such code exists
     */
    Optional<Discount> findByCode(String code);

    /**
     * Whether a code is taken, without loading the row.
     * @param code the code, uppercased
     * @return true if a discount already holds it
     */
    boolean existsByCode(String code);

    /**
     * The discount behind a Stripe coupon, for a webhook that names one.
     * @param stripeCouponId Stripe's coupon identifier
     * @return the discount, or empty
     */
    Optional<Discount> findByStripeCouponId(String stripeCouponId);

    /**
     * The discount behind a Stripe promotion code.
     * @param stripePromotionCodeId Stripe's {@code promo_} identifier
     * @return the discount, or empty
     */
    Optional<Discount> findByStripePromotionCodeId(String stripePromotionCodeId);
}
