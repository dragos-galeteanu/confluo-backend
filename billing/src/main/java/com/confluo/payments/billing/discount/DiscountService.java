package com.confluo.payments.billing.discount;

import com.confluo.common.dto.Paginated;
import com.confluo.common.exception.ResourceNotFoundException;
import com.confluo.common.filter.PaginationFilter;
import com.confluo.common.jpa.mapper.JpaPaginationMapper;
import com.confluo.payments.pricing.Plan;
import com.confluo.payments.pricing.PlanRepository;
import com.confluo.payments.billing.discount.dto.DiscountCreateRequest;
import com.confluo.payments.billing.discount.dto.DiscountResponse;
import com.confluo.payments.marketplace.PaymentRepository;
import com.confluo.payments.marketplace.PaymentStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;


/**
 * Money off: what exists, and whether somebody may use it.
 *
 * <p><strong>No update.</strong> A Stripe coupon's shape cannot be edited, so changing what a
 * discount is worth means creating another and withdrawing this one - which is why this is not a
 * {@code BaseService} and the controller is not a {@code BaseController}. Inheriting an update
 * route to then refuse it would describe an API this resource does not have.</p>
 *
 * <p>Not {@code @Transactional} at the class level: the writes call Stripe, and the local row is
 * committed before they do, as everywhere else in this service.</p>
 *
 * <h2>Where eligibility is decided</h2>
 *
 * <p>Twice, deliberately. Stripe validates a promotion code again when it is redeemed and is the
 * half that cannot be bypassed; {@link #check} is the half that happens before the payer is sent
 * anywhere, so a refusal can be worded by this platform and shown in its own interface. The one
 * rule only this side can enforce is {@code maxRedemptionsPerCustomer}, which a Stripe coupon
 * cannot express at all.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DiscountService {

    private final DiscountRepository repository;
    private final DiscountRedemptionRepository redemptions;
    private final PaymentRepository payments;
    private final PlanRepository plans;
    private final DiscountMapper mapper;
    private final StripeDiscountReconciler reconciler;

    /**
     * Creates a discount and pushes it to Stripe.
     *
     * <p>A coupon always; a promotion code only for a {@link DiscountGrant#PUBLIC_CODE}, because
     * the other three kinds are attached to one person by this platform and there is nothing for
     * anybody to type.</p>
     *
     * @param request the discount
     * @return the discount
     * @throws IllegalStateException if the shape is contradictory, the code is taken, or a tier it
     *         is restricted to has not been reconciled into Stripe
     */
    public DiscountResponse create(DiscountCreateRequest request) {
        String code = request.code() == null ? null : request.code().toUpperCase(Locale.ROOT);
        validate(request, code);

        List<String> restrictToProducts = productsFor(request.appliesToPlanIds());

        Discount discount = new Discount();
        mapper.createEntityFromRequest(discount, request);
        discount.setCode(code);
        if (discount.getAppliesToPlanIds() == null) {
            // The column is NOT NULL; an omitted list means "any tier", not null.
            discount.setAppliesToPlanIds(new ArrayList<>());
        }
        discount = repository.save(discount);

        discount.setStripeCouponId(reconciler.reconcileCoupon(discount, restrictToProducts));
        discount = repository.save(discount);

        if (discount.getGrantKind() == DiscountGrant.PUBLIC_CODE) {
            discount.setStripePromotionCodeId(reconciler.reconcilePromotionCode(discount));
            discount = repository.save(discount);
        }

        log.info("Discount created [id={}, code={}, kind={}, grant={}, coupon={}, promo={}]",
                discount.getId(), discount.getCode(), discount.getKind(), discount.getGrantKind(),
                discount.getStripeCouponId(), discount.getStripePromotionCodeId());
        return respond(discount);
    }

    /**
     * The discount with an id.
     * @param id which discount
     * @return the discount
     * @throws ResourceNotFoundException if there is none
     */
    @Transactional(readOnly = true)
    public Discount getEntityById(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Discount %s not found".formatted(id)));
    }

    /**
     * A discount, as the API returns it.
     * @param id which discount
     * @return the discount
     */
    @Transactional(readOnly = true)
    public DiscountResponse getById(UUID id) {
        return respond(getEntityById(id));
    }

    /**
     * Every discount, paginated, for whoever is running the campaigns.
     * @param filter pagination and sorting
     * @return the discounts
     */
    @Transactional(readOnly = true)
    public Paginated<DiscountResponse> getAll(PaginationFilter filter) {
        return JpaPaginationMapper.findAll(repository, filter, this::respond);
    }

    /**
     * Whether somebody may use a code, and if not, why.
     *
     * <p>The order of the checks is the order the answers are useful in: what the code is, then
     * whether it is live, then whether it has been used up, then whether it fits this particular
     * purchase. A payer who has already used a code should be told that rather than that their
     * order is too small.</p>
     *
     * @param rawCode the code as it was typed; case does not matter
     * @param firebaseUid who is asking
     * @param planId the tier being bought, or null for a one-off purchase
     * @param amount the order, in minor units
     * @param currency the order's currency, uppercase
     * @return the answer
     */
    @Transactional(readOnly = true)
    public DiscountEligibility check(
            String rawCode, String firebaseUid, UUID planId, long amount, String currency) {

        String code = rawCode == null ? null : rawCode.toUpperCase(Locale.ROOT);
        Optional<Discount> found = code == null ? Optional.empty() : repository.findByCode(code);
        if (found.isEmpty()) {
            return DiscountEligibility.refused(
                    DiscountEligibility.Reason.UNKNOWN_CODE, null, code);
        }

        Discount discount = found.get();
        UUID id = discount.getId();

        if (discount.getDisabledAt() != null || discount.getDeletedAt() != null) {
            return DiscountEligibility.refused(DiscountEligibility.Reason.WITHDRAWN, id, code);
        }

        Instant now = Instant.now();
        if (discount.getValidFrom() != null && now.isBefore(discount.getValidFrom())) {
            return DiscountEligibility.refused(DiscountEligibility.Reason.NOT_YET_VALID, id, code);
        }
        if (discount.getValidUntil() != null && now.isAfter(discount.getValidUntil())) {
            return DiscountEligibility.refused(DiscountEligibility.Reason.EXPIRED, id, code);
        }

        if (discount.getMaxRedemptions() != null
                && redemptions.countByDiscountId(id) >= discount.getMaxRedemptions()) {
            return DiscountEligibility.refused(DiscountEligibility.Reason.FULLY_REDEEMED, id, code);
        }

        // The rule Stripe cannot express, and therefore the one reason this check has to exist
        // rather than being left to the redemption.
        if (discount.getMaxRedemptionsPerCustomer() != null
                && redemptions.countByDiscountIdAndFirebaseUid(id, firebaseUid)
                        >= discount.getMaxRedemptionsPerCustomer()) {
            return DiscountEligibility.refused(DiscountEligibility.Reason.ALREADY_REDEEMED, id, code);
        }

        if (discount.isFirstTimeTransactionOnly()
                && payments.existsByFirebaseUidAndStatus(firebaseUid, PaymentStatus.SUCCEEDED)) {
            return DiscountEligibility.refused(
                    DiscountEligibility.Reason.NOT_A_FIRST_TRANSACTION, id, code);
        }

        // A fixed amount off is in one currency and means nothing in another. A percentage applies
        // in all of them, which is why only this branch checks.
        if (discount.getKind() == DiscountKind.FIXED
                && !discount.getCurrency().equals(currency)) {
            return DiscountEligibility.refused(DiscountEligibility.Reason.WRONG_CURRENCY, id, code);
        }

        if (discount.getMinimumAmount() != null) {
            if (!discount.getMinimumAmountCurrency().equals(currency)) {
                return DiscountEligibility.refused(
                        DiscountEligibility.Reason.WRONG_CURRENCY, id, code);
            }
            if (amount < discount.getMinimumAmount()) {
                return DiscountEligibility.refused(
                        DiscountEligibility.Reason.BELOW_MINIMUM, id, code);
            }
        }

        if (!discount.getAppliesToPlanIds().isEmpty()
                && (planId == null || !discount.getAppliesToPlanIds().contains(planId))) {
            return DiscountEligibility.refused(
                    DiscountEligibility.Reason.NOT_FOR_THIS_PLAN, id, code);
        }

        return DiscountEligibility.eligible(id, code);
    }

    /**
     * The discount a code names, refusing if it may not be used.
     *
     * <p>What the checkout paths call. {@link #check} answers a question; this one is the gate,
     * and the reason travels in the message so the refusal a caller sees says which rule stopped
     * it rather than only that something did.</p>
     *
     * @param rawCode the code as it was typed
     * @param firebaseUid who is buying
     * @param planId the tier being bought, or null
     * @param amount the order, in minor units
     * @param currency the order's currency
     * @return the discount
     * @throws IllegalStateException if it may not be used
     */
    @Transactional(readOnly = true)
    public Discount resolve(String rawCode, String firebaseUid, UUID planId, long amount, String currency) {
        DiscountEligibility eligibility = check(rawCode, firebaseUid, planId, amount, currency);
        if (!eligibility.isEligible()) {
            throw new IllegalStateException(
                    "Discount code %s cannot be used: %s".formatted(rawCode, eligibility.reason()));
        }
        return getEntityById(eligibility.discountId());
    }

    /**
     * Withdraws a discount, at Stripe as well as here.
     *
     * <p><strong>Unlike disabling a plan, this has to reach Stripe.</strong> A plan is only ever
     * sold through this service, so withdrawing one locally is enough. A promotion code is typed
     * into Stripe's own hosted page, so a code left active there stays redeemable however withdrawn
     * it is here - and if Stripe refuses the deactivation, this fails rather than reporting a
     * withdrawal that did not happen.</p>
     *
     * @param id which discount
     * @return the discount
     */
    public DiscountResponse disable(UUID id) {
        Discount discount = getEntityById(id);
        if (discount.getDisabledAt() != null) {
            return respond(discount);
        }

        // Stripe first, because the local flag is worthless on its own and the failure has to be
        // the caller's problem.
        reconciler.deactivate(discount.getStripePromotionCodeId());

        discount.setDisabledAt(Instant.now());
        Discount saved = repository.save(discount);
        log.info("Discount withdrawn [id={}, code={}]", saved.getId(), saved.getCode());
        return respond(saved);
    }

    /**
     * Retires a discount for good.
     *
     * <p>Soft-deleted, and its promotion code deactivated at Stripe. The redemption rows stay:
     * they are the record of what a campaign cost, and that question outlives the campaign.</p>
     *
     * @param id which discount
     * @return the discount
     */
    public DiscountResponse delete(UUID id) {
        Discount discount = getEntityById(id);
        reconciler.deactivate(discount.getStripePromotionCodeId());

        discount.setDeletedAt(Instant.now());
        Discount saved = repository.save(discount);
        log.info("Discount deleted [id={}, code={}]", saved.getId(), saved.getCode());
        return respond(saved);
    }

    /**
     * Fills in the redemption count the mapper cannot.
     * @param discount the discount
     * @return the response
     */
    private DiscountResponse respond(Discount discount) {
        DiscountResponse mapped = mapper.toResponse(discount);
        return new DiscountResponse(
                mapped.id(), mapped.code(), mapped.grantKind(), mapped.kind(),
                mapped.percentOff(), mapped.amountOff(), mapped.currency(),
                mapped.duration(), mapped.durationInMonths(),
                mapped.maxRedemptions(), mapped.maxRedemptionsPerCustomer(),
                redemptions.countByDiscountId(discount.getId()),
                mapped.validFrom(), mapped.validUntil(),
                mapped.minimumAmount(), mapped.minimumAmountCurrency(),
                mapped.firstTimeTransactionOnly(), mapped.appliesToPlanIds(),
                mapped.stripeCouponId(), mapped.stripePromotionCodeId(),
                mapped.disabledAt(), mapped.createdAt());
    }

    /**
     * The Stripe products a discount is restricted to.
     *
     * <p>Refuses a tier with no product, because the restriction cannot be expressed at Stripe
     * without one - and a discount silently unrestricted is a discount that applies to everything,
     * which is the expensive way for this to go wrong.</p>
     *
     * @param planIds the tiers, or null
     * @return their Stripe product ids, empty for no restriction
     */
    private List<String> productsFor(List<UUID> planIds) {
        if (planIds == null || planIds.isEmpty()) {
            return List.of();
        }
        List<String> products = new ArrayList<>(planIds.size());
        for (UUID planId : planIds) {
            Plan plan = plans.findById(planId)
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Plan %s not found".formatted(planId)));
            if (plan.getStripeProductId() == null) {
                throw new IllegalStateException(
                        "Plan %s has not been reconciled into Stripe, so a discount cannot be "
                                .formatted(plan.getKey())
                                + "restricted to it; reconcile it first");
            }
            products.add(plan.getStripeProductId());
        }
        return products;
    }

    /**
     * Refuses a contradictory discount before Stripe does.
     *
     * <p>Every rule here is also a {@code CHECK} constraint, which is what actually enforces them.
     * Checked first so the answer names the fields rather than a constraint.</p>
     *
     * @param request what was asked for
     * @param code the uppercased code, or null
     */
    private void validate(DiscountCreateRequest request, String code) {
        if (request.grantKind() == DiscountGrant.PUBLIC_CODE && code == null) {
            throw new IllegalStateException(
                    "A PUBLIC_CODE discount needs a code; without one there is nothing to redeem");
        }
        if (code != null && repository.existsByCode(code)) {
            throw new IllegalStateException("A discount with the code %s already exists".formatted(code));
        }

        if (request.kind() == DiscountKind.PERCENTAGE) {
            if (request.percentOff() == null) {
                throw new IllegalStateException("A PERCENTAGE discount needs percentOff");
            }
            if (request.amountOff() != null || request.currency() != null) {
                throw new IllegalStateException(
                        "A PERCENTAGE discount takes neither amountOff nor currency: a proportion "
                                + "applies in every currency");
            }
        } else {
            if (request.amountOff() == null || request.currency() == null) {
                throw new IllegalStateException(
                        "A FIXED discount needs both amountOff and a currency: an amount off means "
                                + "nothing without one");
            }
            if (request.percentOff() != null) {
                throw new IllegalStateException("A FIXED discount takes no percentOff");
            }
        }

        boolean repeating = request.duration() == com.confluo.payments.billing.discount.DiscountDuration.REPEATING;
        if (repeating && request.durationInMonths() == null) {
            throw new IllegalStateException(
                    "A REPEATING discount needs durationInMonths; without one it has no end");
        }
        if (!repeating && request.durationInMonths() != null) {
            throw new IllegalStateException(
                    "durationInMonths only means something for a REPEATING discount");
        }

        if ((request.minimumAmount() == null) != (request.minimumAmountCurrency() == null)) {
            throw new IllegalStateException(
                    "A minimum amount and its currency are one fact; neither is valid alone");
        }
        if (request.validFrom() != null && request.validUntil() != null
                && !request.validUntil().isAfter(request.validFrom())) {
            throw new IllegalStateException("validUntil has to be after validFrom");
        }
    }
}
