package com.confluo.payments.billing.subscription;

import com.confluo.common.exception.ResourceNotFoundException;
import com.confluo.payments.pricing.Plan;
import com.confluo.payments.pricing.PlanPrice;
import com.confluo.payments.pricing.PlanPriceRepository;
import com.confluo.payments.pricing.PlanRepository;
import com.confluo.payments.billing.discount.Discount;
import com.confluo.payments.billing.discount.DiscountService;
import com.confluo.payments.billing.subscription.dto.SubscriptionCheckoutRequest;
import com.confluo.payments.billing.subscription.dto.SubscriptionCheckoutResponse;
import com.confluo.payments.marketplace.CheckoutProperties;
import com.confluo.payments.customer.StripeCustomer;
import com.confluo.payments.customer.StripeCustomerService;
import com.confluo.stripe.CheckoutSessionGateway;
import com.confluo.stripe.StripeOperationException;
import com.stripe.exception.StripeException;
import com.stripe.param.checkout.SessionCreateParams;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;


/**
 * Sets up a subscription so somebody can start paying for a tier.
 *
 * <h2>No local row, unlike an order checkout</h2>
 *
 * <p>{@code OrderCheckoutService} commits a payment row before calling Stripe, because a charge
 * Stripe knows about and this service does not is money nobody can account for. A subscription
 * cannot be written in advance: its identifier is Stripe's, it is {@code NOT NULL} and unique, and
 * there is nothing to put in it until Stripe has made one.</p>
 *
 * <p>So the link runs the other way. The session carries the plan price and the discount in
 * {@code subscription_data.metadata}, and {@code customer.subscription.created} builds the local row
 * from them. Which means <strong>a 200 from here is not somebody subscribing</strong> - it is a
 * session existing, and only the webhook says more.</p>
 *
 * <h2>Why Stripe is not allowed to collect the promotion code</h2>
 *
 * <p>{@code allow_promotion_codes} is deliberately never set. It would let a subscriber type a code
 * on Stripe's page, where Stripe validates its own restrictions - and Stripe cannot express a
 * per-person redemption limit, which is the one rule this platform has that it does not. A code
 * collected there would bypass {@link DiscountService#check} entirely, so a "once per customer"
 * discount would be once per attempt.</p>
 *
 * <p>The cost is that a subscriber has to enter the code in this platform's own interface, which is
 * where it can be checked and refused with a reason.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SubscriptionCheckoutService {

    /** The states in which somebody already has what a new subscription would sell them. */
    private static final List<SubscriptionStatus> LIVE = List.of(
            SubscriptionStatus.TRIALING, SubscriptionStatus.ACTIVE,
            SubscriptionStatus.PAST_DUE, SubscriptionStatus.UNPAID);

    private final PlanRepository plans;
    private final PlanPriceRepository prices;
    private final SubscriptionRepository subscriptions;
    private final StripeCustomerService customers;
    private final DiscountService discounts;
    private final CheckoutSessionGateway gateway;
    private final CheckoutProperties checkout;

    /**
     * Sets up a subscription checkout.
     *
     * @param request which tier, for whom, at what period
     * @return where to send them
     * @throws ResourceNotFoundException if the tier does not exist, or is not sold on those terms
     * @throws IllegalStateException if the tier has not been reconciled into Stripe, if they
     *         already hold a live subscription to this side of the marketplace, or if the discount
     *         code cannot be used
     */
    @Transactional(readOnly = true)
    public SubscriptionCheckoutResponse checkout(SubscriptionCheckoutRequest request) {
        Plan plan = plans.findByKey(request.planKey().toLowerCase(Locale.ROOT))
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Plan %s not found".formatted(request.planKey())));

        if (plan.getDisabledAt() != null || plan.getDeletedAt() != null) {
            throw new IllegalStateException(
                    "Plan %s is no longer offered".formatted(plan.getKey()));
        }

        PlanPrice price = prices
                .findByPlanIdAndCurrencyAndBillingIntervalAndIntervalCountAndActiveTrue(
                        plan.getId(), request.currency(), request.billingInterval(),
                        request.intervalCount())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Plan %s is not sold at %d %s per %d %s".formatted(
                                plan.getKey(), request.intervalCount(), request.currency(),
                                request.intervalCount(), request.billingInterval())));

        if (price.getStripePriceId() == null) {
            throw new IllegalStateException(
                    "The price of plan %s has not been reconciled into Stripe, so nothing can be "
                            .formatted(plan.getKey()) + "subscribed at it");
        }

        // The guard that stops one person being billed twice for the same thing. Stripe will
        // create a second subscription to the same price without complaint.
        if (subscriptions.existsLiveForAudience(
                request.buyerFirebaseUid(), LIVE, plan.getAudience())) {
            throw new IllegalStateException(
                    "%s already has a live %s subscription; change it rather than adding another"
                            .formatted(request.buyerFirebaseUid(), plan.getAudience()));
        }

        Discount discount = resolveDiscount(request, plan, price);
        StripeCustomer buyer = buyerOf(request.buyerFirebaseUid());

        CheckoutSessionGateway.CheckoutSession session = gateway.create(
                sessionParams(request, plan, price, buyer, discount),
                "subscription:%s:%s".formatted(request.buyerFirebaseUid(), price.getId()));

        log.info("Subscription checkout created [session={}, uid={}, plan={}, price={}, discount={}]",
                session.id(), request.buyerFirebaseUid(), plan.getKey(), price.getId(),
                discount == null ? "none" : discount.getCode());

        return new SubscriptionCheckoutResponse(
                session.id(),
                session.url(),
                plan.getKey(),
                price.getUnitAmount(),
                price.getCurrency(),
                price.getBillingInterval(),
                price.getIntervalCount(),
                price.getTrialPeriodDays(),
                discount == null ? null : discount.getCode());
    }

    /**
     * The discount a code names, if one was given.
     *
     * <p>Checked against this particular purchase - the tier, the amount and the currency - rather
     * than merely existing, which is what makes a code restricted to one tier or above a threshold
     * mean anything.</p>
     *
     * @param request the request
     * @param plan the tier
     * @param price its price
     * @return the discount, or null if no code was given
     */
    private Discount resolveDiscount(
            SubscriptionCheckoutRequest request, Plan plan, PlanPrice price) {
        if (request.promotionCode() == null || request.promotionCode().isBlank()) {
            return null;
        }
        return discounts.resolve(request.promotionCode(), request.buyerFirebaseUid(),
                plan.getId(), price.getUnitAmount(), price.getCurrency());
    }

    private StripeCustomer buyerOf(String firebaseUid) {
        try {
            return customers.resolveOrCreate(firebaseUid);
        } catch (StripeException e) {
            throw new StripeOperationException(
                    "Could not resolve a Stripe customer for %s".formatted(firebaseUid), e);
        }
    }

    /**
     * What the session should be.
     *
     * <p>The parts worth knowing about:</p>
     *
     * <ul>
     *   <li>{@code mode = SUBSCRIPTION} and a line item naming the Stripe price, rather than inline
     *       price data: a recurring charge has to be against a Price that Stripe owns, which is
     *       what the catalog reconciliation exists to produce;</li>
     *   <li>{@code subscription_data.metadata} carries the plan price, the discount and the uid.
     *       This is the only link from the session back to this platform, because there is no local
     *       row to point at - see the class javadoc;</li>
     *   <li>the trial comes from the price, so a tier's trial is a property of what is being sold
     *       rather than of who is buying;</li>
     *   <li>a discount is attached as a <strong>coupon</strong>, not as a promotion code. Both would
     *       work, and the coupon is the one that cannot be re-collected or altered on Stripe's page;</li>
     *   <li>{@code automatic_tax} with {@code liability = self}, as for an order: the platform is
     *       the merchant of record on its own subscriptions too.</li>
     * </ul>
     *
     * @param request the request
     * @param plan the tier
     * @param price its price
     * @param buyer the Stripe customer subscribing
     * @param discount the discount to apply, or null
     * @return the parameters
     */
    private SessionCreateParams sessionParams(
            SubscriptionCheckoutRequest request, Plan plan, PlanPrice price,
            StripeCustomer buyer, Discount discount) {

        SessionCreateParams.SubscriptionData.Builder subscriptionData =
                SessionCreateParams.SubscriptionData.builder()
                        .putMetadata("plan_price_id", price.getId().toString())
                        .putMetadata("plan_key", plan.getKey())
                        .putMetadata("firebase_uid", request.buyerFirebaseUid());

        if (price.getTrialPeriodDays() != null) {
            subscriptionData.setTrialPeriodDays(price.getTrialPeriodDays().longValue());
        }
        if (discount != null) {
            subscriptionData.putMetadata("discount_id", discount.getId().toString());
        }

        SessionCreateParams.Builder params = SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.SUBSCRIPTION)
                .setSuccessUrl(checkout.successUrl())
                .setCancelUrl(checkout.cancelUrl())
                .setCustomer(buyer.getStripeCustomerId())
                .setBillingAddressCollection(SessionCreateParams.BillingAddressCollection.REQUIRED)
                .setCustomerUpdate(SessionCreateParams.CustomerUpdate.builder()
                        .setAddress(SessionCreateParams.CustomerUpdate.Address.AUTO)
                        .build())
                .setAutomaticTax(SessionCreateParams.AutomaticTax.builder()
                        .setEnabled(true)
                        .setLiability(SessionCreateParams.AutomaticTax.Liability.builder()
                                .setType(SessionCreateParams.AutomaticTax.Liability.Type.SELF)
                                .build())
                        .build())
                .setSubscriptionData(subscriptionData.build())
                .addLineItem(SessionCreateParams.LineItem.builder()
                        .setQuantity(1L)
                        .setPrice(price.getStripePriceId())
                        .build())
                .putMetadata("plan_key", plan.getKey())
                .putMetadata("firebase_uid", request.buyerFirebaseUid());

        if (discount != null) {
            params.addDiscount(SessionCreateParams.Discount.builder()
                    .setCoupon(discount.getStripeCouponId())
                    .build());
        }
        // No setAllowPromotionCodes: see the class javadoc. Stripe cannot enforce the per-person
        // limit, so a code has to come through this service to be checked.

        return params.build();
    }
}
