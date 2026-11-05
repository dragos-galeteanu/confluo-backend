package com.confluo.payments.marketplace.refund;

import com.confluo.payments.marketplace.refund.dto.RefundCreateRequest;
import com.confluo.payments.marketplace.refund.dto.RefundResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;


/**
 * Where a refund is asked for by a person.
 *
 * <p>One of the two ways a refund happens on this platform, and the only one a person starts. The
 * other is an order being cancelled, which arrives as an event rather than a request - see
 * {@code OnOrderCancelled} - and is why this controller is for people only: interservice
 * communication here is AMQP, so there is no service caller for these routes to authenticate.</p>
 *
 * <p>Refunding is <strong>support's</strong>, not the payer's. A payer who wants their money back
 * cancels the order, because whether they may is a question about the order rather than about the
 * payment; {@code RefundAuthorizationPolicy} has the reasoning. The read route below is open to
 * them, since seeing a refund is not issuing one.</p>
 *
 * <p>Not a {@code BaseController}. A refund has no update and no delete - Stripe will not change
 * one once it exists - and the only list worth having is per payment, so the CRUD shape would be
 * three denials and a route addressed by the wrong id.</p>
 */
@RestController
@RequestMapping("/api/payments/refunds")
@RequiredArgsConstructor
@Tag(name = "Refunds")
public class RefundController {

    private final RefundService refunds;
    private final RefundAuthorizationPolicy policy;

    /**
     * Sends money back off one payment.
     *
     * <p>Safe to call twice with the same body: the refund is keyed by the payment and the amount,
     * so a double tap or a retry after a lost response resolves to the one refund Stripe already
     * made rather than refunding again. Deliberately issuing a <em>second</em> refund of the same
     * amount needs an {@code idempotencyKey} in the request to say so.</p>
     *
     * <p>A 200 means Stripe has accepted the refund, not that the payer has their money: a refund
     * is submitted to their bank and is normally {@code PENDING} at this point. It can still fail
     * days later if the card has closed, which arrives on a webhook and moves the status.</p>
     *
     * <p>{@link Authentication} is read here and passed to the policy for one thing: what to record
     * as the refund's provenance. That is an authorization question rather than a request field, so
     * the policy answers it - and refuses, rather than guessing, for a caller it did not expect.</p>
     *
     * @param request what to refund and why
     * @param authentication the caller, whose role becomes the refund's recorded provenance
     * @return the refund as recorded
     */
    @PostMapping
    @Operation(
            summary = "Refund a payment",
            description = "Idempotent on the payment and the amount: asking twice gives back the "
                    + "same refund rather than refunding twice. A 200 means Stripe accepted it, "
                    + "not that the money has arrived - the status says which.")
    @PreAuthorize("@refundPolicy.canCreate(authentication, #request)")
    public ResponseEntity<RefundResponse> refund(
            @Valid @RequestBody RefundCreateRequest request,
            Authentication authentication) {
        return ResponseEntity.ok(refunds.refund(request, policy.initiatorFor(authentication)));
    }

    /**
     * What has been refunded against one payment.
     *
     * <p>A list because a payment can be refunded in parts, by different people, for different
     * reasons - and each row says which, which is the question actually being asked of a payment
     * whose total no longer matches what was charged.</p>
     *
     * <p>Includes refunds made in Stripe's own dashboard, recorded by the webhook as
     * {@code STRIPE_DASHBOARD}: leaving those out would make this list disagree with the payment's
     * refunded total.</p>
     *
     * @param paymentId the payment
     * @return its refunds, newest first
     */
    @GetMapping("/payment/{paymentId}")
    @Operation(
            summary = "List the refunds against a payment",
            description = "Newest first, including refunds made in Stripe's dashboard rather than "
                    + "through this service.")
    @PreAuthorize("@refundPolicy.canGetById(authentication, #paymentId)")
    public ResponseEntity<List<RefundResponse>> forPayment(@PathVariable UUID paymentId) {
        return ResponseEntity.ok(refunds.listFor(paymentId));
    }
}
