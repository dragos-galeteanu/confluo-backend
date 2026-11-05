package com.confluo.payments.pricing;

import com.confluo.payments.pricing.dto.PlanPriceCreateRequest;
import com.confluo.payments.pricing.dto.PlanPriceResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;


/**
 * What a tier costs.
 *
 * <p>Not a {@link com.confluo.common.controller.BaseController}, and nested under the tier it
 * belongs to. A price is not an independent resource: it has no update, because Stripe's prices are
 * immutable and a change is a new price; and it has no delete, because the subscriptions on it are
 * still billed at it. What it has is add, list and withdraw, which is three routes rather than six
 * - and inheriting six to then refuse three would describe an API this resource does not have.</p>
 */
@RestController
@RequestMapping("/api/payments/plans")
@RequiredArgsConstructor
@Tag(name = "Plan prices")
public class PlanPriceController {

    private final PlanPriceService service;

    /**
     * Adds a price to a tier, retiring whatever it replaces.
     *
     * <p>This is also how a tier's price is <strong>changed</strong>: the new price becomes the one
     * sold and its predecessor for the same currency and period is deactivated, at Stripe as well
     * as here. Subscriptions already running keep being billed at the old price, which is Stripe's
     * behaviour and the reason prices are added rather than edited.</p>
     *
     * @param planId which tier
     * @param request the price
     * @return the price
     */
    @PostMapping("/{planId}/prices")
    @Operation(
            summary = "Add a price to a plan",
            description = "Also how a price is changed: the new one replaces the active price for "
                    + "the same currency and period, and subscriptions already running keep the old one.")
    @PreAuthorize("@catalogPolicy.canWritePrices(authentication)")
    public ResponseEntity<PlanPriceResponse> add(
            @PathVariable UUID planId,
            @Valid @RequestBody PlanPriceCreateRequest request) {
        return new ResponseEntity<>(service.add(planId, request), HttpStatus.CREATED);
    }

    /**
     * The prices a tier is currently sold at.
     * @param planId which tier
     * @return its active prices
     */
    @GetMapping("/{planId}/prices")
    @Operation(summary = "List a plan's active prices")
    @PreAuthorize("@catalogPolicy.canReadPrices(authentication)")
    public ResponseEntity<List<PlanPriceResponse>> getActive(@PathVariable UUID planId) {
        return ResponseEntity.ok(service.getActive(planId));
    }

    /**
     * Stops selling at a price without replacing it.
     *
     * <p>For withdrawing a currency or a billing period. On {@code /prices/{priceId}} rather than
     * under the plan, because a price's id identifies it on its own and threading the plan through
     * would invite the two to disagree.</p>
     *
     * @param priceId which price
     * @return the price
     */
    @DeleteMapping("/prices/{priceId}")
    @Operation(summary = "Stop selling at a price")
    @PreAuthorize("@catalogPolicy.canWritePrices(authentication)")
    public ResponseEntity<PlanPriceResponse> deactivate(@PathVariable UUID priceId) {
        return ResponseEntity.ok(service.deactivate(priceId));
    }
}
