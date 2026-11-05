package com.confluo.payments.billing.discount;

import com.confluo.common.dto.Paginated;
import com.confluo.common.filter.PaginationFilter;
import com.confluo.payments.billing.discount.dto.DiscountCreateRequest;
import com.confluo.payments.billing.discount.dto.DiscountResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;


/**
 * Money off.
 *
 * <p>Not a {@link com.confluo.common.controller.BaseController}, because there is no update: a
 * Stripe coupon's shape is immutable, so changing what a discount is worth means creating another
 * and withdrawing this one. Five routes rather than six, and the missing one is missing on
 * purpose.</p>
 */
@RestController
@RequestMapping("/api/payments/discounts")
@RequiredArgsConstructor
@Tag(name = "Discounts")
public class DiscountController {

    private final DiscountService service;

    /**
     * Creates a discount and pushes it to Stripe.
     *
     * @param request the discount
     * @return the discount, with the Stripe ids it was given
     */
    @PostMapping
    @Operation(
            summary = "Create a discount",
            description = "Creates a Stripe coupon, and a promotion code as well when the discount "
                    + "is a public code. There is no update: change one by creating another.")
    @PreAuthorize("@discountPolicy.canCreate(authentication, #request)")
    public ResponseEntity<DiscountResponse> create(@Valid @RequestBody DiscountCreateRequest request) {
        return new ResponseEntity<>(service.create(request), HttpStatus.CREATED);
    }

    /**
     * One discount.
     * @param id which discount
     * @return the discount
     */
    @GetMapping("/{id}")
    @Operation(summary = "Get a discount")
    @PreAuthorize("@discountPolicy.canGetById(authentication, #id)")
    public ResponseEntity<DiscountResponse> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(service.getById(id));
    }

    /**
     * Every discount, paginated.
     *
     * <p>Admin or Support only, unlike the plan listing. Which codes exist and what each is worth
     * is a marketing position rather than a price list.</p>
     *
     * @param filter pagination and sorting
     * @return the discounts
     */
    @GetMapping
    @Operation(summary = "List discounts")
    @PreAuthorize("@discountPolicy.canGetAll(authentication, #filter)")
    public ResponseEntity<Paginated<DiscountResponse>> getAll(
            @Valid @ModelAttribute PaginationFilter filter) {
        return ResponseEntity.ok(service.getAll(filter));
    }

    /**
     * Whether a code can be used for a particular purchase, and if not, why.
     *
     * <p>For the checkout page, so a payer finds out before being sent to Stripe and in this
     * platform's own wording. A caller may ask about themselves; Support may ask on anybody's
     * behalf, because "why will this code not work for me" is a support question that can only be
     * answered as that person.</p>
     *
     * <p>It answers 200 with a reason either way rather than a 4xx for a refusal: an ineligible
     * code is a normal outcome of asking, not a failed request.</p>
     *
     * @param code the code as it was typed; case does not matter
     * @param firebaseUid who would be using it
     * @param amount the order, in minor units
     * @param currency the order's currency, uppercase
     * @param planId the tier being bought, or null for a one-off purchase
     * @return whether it may be used, and the reason
     */
    @GetMapping("/check")
    @Operation(
            summary = "Check whether a discount code can be used",
            description = "Answers 200 with a reason whether or not the code is eligible: being "
                    + "refused is a normal outcome, not a failed request.")
    @PreAuthorize("@discountPolicy.canCheck(authentication, #firebaseUid)")
    public ResponseEntity<DiscountEligibility> check(
            @RequestParam String code,
            @RequestParam String firebaseUid,
            @RequestParam long amount,
            @RequestParam String currency,
            @RequestParam(required = false) UUID planId) {
        return ResponseEntity.ok(service.check(code, firebaseUid, planId, amount, currency));
    }

    /**
     * Withdraws a discount.
     *
     * <p>Reaches Stripe, unlike disabling a plan: a promotion code is typed into Stripe's own
     * hosted page, so a code left active there stays redeemable. If Stripe refuses, this fails
     * rather than reporting a withdrawal that did not happen.</p>
     *
     * @param id which discount
     * @return the discount
     */
    @DeleteMapping("/{id}/disable")
    @Operation(summary = "Withdraw a discount, at Stripe as well as here")
    @PreAuthorize("@discountPolicy.canDisable(authentication)")
    public ResponseEntity<DiscountResponse> disable(@PathVariable UUID id) {
        return ResponseEntity.ok(service.disable(id));
    }

    /**
     * Retires a discount for good.
     *
     * <p>The redemption rows stay: they are the record of what a campaign cost, and that question
     * outlives the campaign.</p>
     *
     * @param id which discount
     * @return the discount
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "Retire a discount")
    @PreAuthorize("@discountPolicy.canDelete(authentication, #id)")
    public ResponseEntity<DiscountResponse> delete(@PathVariable UUID id) {
        return ResponseEntity.ok(service.delete(id));
    }
}
