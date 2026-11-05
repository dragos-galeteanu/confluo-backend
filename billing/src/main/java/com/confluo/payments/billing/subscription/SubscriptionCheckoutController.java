package com.confluo.payments.billing.subscription;

import com.confluo.payments.billing.subscription.dto.SubscriptionCheckoutRequest;
import com.confluo.payments.billing.subscription.dto.SubscriptionCheckoutResponse;
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
 * Where a subscription is set up.
 *
 * <p>On the same path as {@code CheckoutController}, which sets up the payment for a sale, and in a
 * different package on purpose: a subscription checkout is billing's, a sale's is the
 * marketplace's, and the only thing they share is that both answer with a Stripe URL. Spring MVC
 * maps the two routes independently, and the shared {@code @Tag} keeps them in one group in the API
 * documentation, so the split is invisible to a caller - the URL has not changed.</p>
 *
 * <p>Not a {@code BaseController}: a checkout is not a resource with a lifecycle, it is an action
 * that happens to leave a subscription behind. {@code SubscriptionController} has the routes that
 * read and change one.</p>
 */
@RestController
@RequestMapping("/api/payments/checkout")
@RequiredArgsConstructor
@Tag(name = "Checkout")
public class SubscriptionCheckoutController {

    private final SubscriptionCheckoutService subscriptions;

    /**
     * Sets up a subscription to a tier and says where to send the subscriber.
     *
     * <p><strong>Not idempotent in the way the order route is</strong>, and it cannot be: a
     * subscription has no local row until Stripe creates one, so there is nothing to key a retry
     * against. What stands in for it is a guard - somebody who already holds a live subscription to
     * this side of the marketplace is refused, which is the mistake that would otherwise bill one
     * person twice for the same thing.</p>
     *
     * <p>A 200 means a session exists. It does <strong>not</strong> mean anybody is subscribed -
     * only {@code customer.subscription.created} says that, and it is what writes the local
     * row.</p>
     *
     * <p>A discount code goes in the request, never collected by Stripe: Stripe cannot enforce a
     * per-person redemption limit, so a code typed on its page would bypass the only check that
     * can.</p>
     *
     * @param request which tier, for whom, at what period
     * @return where to send the subscriber
     */
    @PostMapping("/subscription")
    @Operation(
            summary = "Set up a subscription to a plan",
            description = "A 200 records nothing locally: a subscription exists only once Stripe "
                    + "creates it. Refuses somebody who already holds a live subscription to the "
                    + "same side of the marketplace.")
    @PreAuthorize("@subscriptionCheckoutPolicy.canCreate(authentication, #request)")
    public ResponseEntity<SubscriptionCheckoutResponse> subscription(
            @Valid @RequestBody SubscriptionCheckoutRequest request) {
        return ResponseEntity.ok(subscriptions.checkout(request));
    }
}
