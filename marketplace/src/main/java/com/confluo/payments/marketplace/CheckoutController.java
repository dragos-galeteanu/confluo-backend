package com.confluo.payments.marketplace;

import com.confluo.payments.marketplace.dto.CheckoutSessionResponse;
import com.confluo.payments.marketplace.dto.OrderCheckoutRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Where the payment for a sale is set up.
 *
 * <p>Subscriptions are set up by {@code SubscriptionCheckoutController}, on this same path and in
 * billing's package. Both answer with a Stripe URL and that is all they have in common: what the
 * platform keeps from a sale is this package's business, what a tier costs is billing's, and
 * keeping one controller for both was what made the marketplace depend on billing.</p>
 *
 * <p>Not a {@code BaseController}: a checkout is not a resource with a lifecycle, it is an action
 * that happens to leave a payment behind. The payment itself gets the usual read routes when they
 * arrive, on their own controller.</p>
 */
@RestController
@RequestMapping("/api/payments/checkout")
@RequiredArgsConstructor
@Tag(name = "Checkout")
public class CheckoutController {

    private final OrderCheckoutService orders;

    /**
     * Sets up the payment for one order and says where to send the payer.
     *
     * <p>Safe to call twice. The payment is keyed by the order it is for, so a repeated request
     * finds the payment that already exists and either sends the payer back to the same Stripe
     * page or makes a fresh one if the old one has expired. It refuses only when the order has
     * already been paid, or when the same order arrives at a different price.</p>
     *
     * <p>A 200 means a payment has been recorded and a payer can be sent to Stripe. It does
     * <strong>not</strong> mean anybody has paid - only {@code checkout.session.completed} and
     * {@code payment_intent.succeeded} say that, which is why the response carries the payment's
     * status rather than implying one.</p>
     *
     * @param request what is being sold, to whom, by whom
     * @return where to send the payer
     */
    @PostMapping("/order")
    @Operation(
            summary = "Set up the payment for an order",
            description = "Idempotent on the order reference: asking twice resumes the same "
                    + "payment rather than charging twice. A 200 records a payment, it does not "
                    + "mean one has been made.")
    @PreAuthorize("@checkoutPolicy.canCreate(authentication, #request)")
    public ResponseEntity<CheckoutSessionResponse> order(
            @Valid @RequestBody OrderCheckoutRequest request) {
        return ResponseEntity.ok(orders.checkout(request));
    }
}
