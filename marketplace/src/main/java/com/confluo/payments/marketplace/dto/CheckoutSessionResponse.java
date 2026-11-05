package com.confluo.payments.marketplace.dto;

import com.confluo.payments.marketplace.PaymentStatus;

import java.util.UUID;

/**
 * Where to send the payer, and what this service recorded before sending them.
 *
 * <p>{@code checkoutUrl} is the only field the caller has to act on. The rest is so that a caller
 * which asked twice can tell what happened without a second request: the same {@code paymentId}
 * means it reached the payment that already existed.</p>
 *
 * @param paymentId this service's own id for the payment, which is also the session's
 *                  {@code client_reference_id} at Stripe
 * @param stripeCheckoutSessionId Stripe's {@code cs_} identifier
 * @param checkoutUrl Stripe's hosted page, where the payer actually pays
 * @param status where the payment has got to, which is {@code REQUIRES_PAYMENT} for a new one
 * @param amount what will be charged before tax, in minor units
 * @param currency uppercase ISO 4217
 * @param applicationFeeAmount the platform's cut, in minor units
 */
public record CheckoutSessionResponse(
        UUID paymentId,
        String stripeCheckoutSessionId,
        String checkoutUrl,
        PaymentStatus status,
        long amount,
        String currency,
        Long applicationFeeAmount
) {
}
