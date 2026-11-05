package com.confluo.payments.billing.discount;

import com.confluo.stripe.StripeOperationException;
import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.net.RequestOptions;
import com.stripe.param.CouponCreateParams;
import com.stripe.param.PromotionCodeCreateParams;
import com.stripe.param.PromotionCodeUpdateParams;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;


/**
 * Pushes a discount into Stripe as a coupon, and its code as a promotion code.
 *
 * <p>Two objects for one discount, because Stripe splits what this platform keeps together: a
 * <strong>coupon</strong> is the shape of the reduction and a <strong>promotion code</strong> is a
 * string a payer may type to claim it. A granted discount - a referral, a goodwill credit - needs
 * only the coupon, because nobody types anything to get it.</p>
 *
 * <p>Neither can be edited into a different shape afterwards, which is why there is no update here
 * and no update on the discount itself. A promotion code's {@code active} flag is the one thing
 * that may change, and {@link #deactivate} is how withdrawing a discount reaches Stripe.</p>
 *
 * <p>That last part matters more here than for a plan. A plan is only ever sold through this
 * service, so withdrawing one locally is enough; a promotion code is typed <strong>into Stripe's
 * own hosted page</strong>, so a code left active there stays redeemable however withdrawn it is
 * here.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StripeDiscountReconciler {

    private final StripeClient stripe;

    /**
     * Creates the coupon for a discount, and returns its id.
     *
     * @param discount the discount
     * @param restrictToProducts Stripe product ids the discount is limited to, or empty for any.
     *                           Resolved by the caller, because the mapping from this platform's
     *                           plans to Stripe's products is the caller's to know
     * @return Stripe's coupon identifier
     * @throws StripeOperationException if Stripe refuses
     */
    public String reconcileCoupon(Discount discount, List<String> restrictToProducts) {
        CouponCreateParams.Builder params = CouponCreateParams.builder()
                .setDuration(durationOf(discount.getDuration()))
                .putMetadata("discount_grant_kind", discount.getGrantKind().name());

        if (discount.getKind() == DiscountKind.PERCENTAGE) {
            params.setPercentOff(discount.getPercentOff());
        } else {
            params.setAmountOff(discount.getAmountOff())
                    // Stripe speaks lowercase; the column is uppercase, and this is the boundary.
                    .setCurrency(discount.getCurrency().toLowerCase(Locale.ROOT));
        }

        if (discount.getDuration() == DiscountDuration.REPEATING) {
            params.setDurationInMonths(discount.getDurationInMonths().longValue());
        }
        if (discount.getMaxRedemptions() != null) {
            params.setMaxRedemptions(discount.getMaxRedemptions().longValue());
        }
        if (discount.getValidUntil() != null) {
            // Stripe's own expiry on the coupon. The validity window is enforced here too, which
            // is what lets a refusal happen before the payer is sent anywhere.
            params.setRedeemBy(discount.getValidUntil().getEpochSecond());
        }
        if (discount.getCode() != null) {
            params.setName(discount.getCode());
        }
        if (!restrictToProducts.isEmpty()) {
            CouponCreateParams.AppliesTo.Builder appliesTo = CouponCreateParams.AppliesTo.builder();
            restrictToProducts.forEach(appliesTo::addProduct);
            params.setAppliesTo(appliesTo.build());
        }

        try {
            String id = stripe.coupons().create(params.build(),
                    RequestOptions.builder()
                            .setIdempotencyKey("coupon:" + discount.getId())
                            .build()).getId();
            log.info("Stripe coupon created for discount {} [coupon={}]", discount.getId(), id);
            return id;
        } catch (StripeException e) {
            throw new StripeOperationException(
                    "Could not create a Stripe coupon for discount %s".formatted(discount.getId()), e);
        }
    }

    /**
     * Creates the promotion code a payer types, and returns its id.
     *
     * <p>The restrictions Stripe can express are set here as well as being checked locally:
     * {@code firstTimeTransaction} and the minimum amount. The per-customer limit is not among
     * them, which is the whole reason this service counts redemptions itself.</p>
     *
     * @param discount the discount, which must already have a coupon
     * @return Stripe's promotion code identifier
     * @throws IllegalStateException if the discount has no coupon yet
     * @throws StripeOperationException if Stripe refuses
     */
    public String reconcilePromotionCode(Discount discount) {
        if (discount.getStripeCouponId() == null) {
            throw new IllegalStateException(
                    "Discount %s has no Stripe coupon yet, so it cannot have a promotion code"
                            .formatted(discount.getId()));
        }

        PromotionCodeCreateParams.Restrictions.Builder restrictions =
                PromotionCodeCreateParams.Restrictions.builder()
                        .setFirstTimeTransaction(discount.isFirstTimeTransactionOnly());
        if (discount.getMinimumAmount() != null) {
            restrictions.setMinimumAmount(discount.getMinimumAmount())
                    .setMinimumAmountCurrency(
                            discount.getMinimumAmountCurrency().toLowerCase(Locale.ROOT));
        }

        PromotionCodeCreateParams.Builder params = PromotionCodeCreateParams.builder()
                // Wrapped in a promotion rather than passed as a bare coupon id: that is the shape
                // this API version takes, and the older flat `coupon` field is gone.
                .setPromotion(PromotionCodeCreateParams.Promotion.builder()
                        .setType(PromotionCodeCreateParams.Promotion.Type.COUPON)
                        .setCoupon(discount.getStripeCouponId())
                        .build())
                .setCode(discount.getCode())
                .setRestrictions(restrictions.build());

        if (discount.getMaxRedemptions() != null) {
            params.setMaxRedemptions(discount.getMaxRedemptions().longValue());
        }
        if (discount.getValidUntil() != null) {
            params.setExpiresAt(discount.getValidUntil().getEpochSecond());
        }

        try {
            String id = stripe.promotionCodes().create(params.build(),
                    RequestOptions.builder()
                            .setIdempotencyKey("promo:" + discount.getId())
                            .build()).getId();
            log.info("Stripe promotion code created for discount {} [code={}, promo={}]",
                    discount.getId(), discount.getCode(), id);
            return id;
        } catch (StripeException e) {
            throw new StripeOperationException(
                    "Could not create a Stripe promotion code for discount %s"
                            .formatted(discount.getId()), e);
        }
    }

    /**
     * Stops Stripe accepting a promotion code.
     *
     * <p><strong>Not best-effort, unlike archiving a plan's price.</strong> A failure here is
     * propagated, because a promotion code is redeemed at Stripe's own hosted page: a code still
     * active there is still money off, whatever this service believes. Telling an administrator the
     * withdrawal failed is the only honest outcome.</p>
     *
     * @param stripePromotionCodeId Stripe's promotion code identifier
     * @throws StripeOperationException if Stripe refuses
     */
    public void deactivate(String stripePromotionCodeId) {
        if (stripePromotionCodeId == null) {
            return;
        }
        try {
            stripe.promotionCodes().update(stripePromotionCodeId,
                    PromotionCodeUpdateParams.builder().setActive(false).build());
            log.info("Stripe promotion code {} deactivated", stripePromotionCodeId);
        } catch (StripeException e) {
            throw new StripeOperationException(
                    "Could not deactivate Stripe promotion code %s; it is still redeemable at "
                            .formatted(stripePromotionCodeId) + "Stripe's hosted page", e);
        }
    }

    private CouponCreateParams.Duration durationOf(DiscountDuration duration) {
        return switch (duration) {
            case ONCE -> CouponCreateParams.Duration.ONCE;
            case REPEATING -> CouponCreateParams.Duration.REPEATING;
            case FOREVER -> CouponCreateParams.Duration.FOREVER;
        };
    }
}
