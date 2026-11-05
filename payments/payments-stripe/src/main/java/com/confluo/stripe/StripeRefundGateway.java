package com.confluo.stripe;

import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.Refund;
import com.stripe.net.RequestOptions;
import com.stripe.param.RefundCreateParams;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * {@link RefundGateway} against Stripe.
 *
 * <p>Nothing but the call, the idempotency key and the mapping. Any decision that appears here is
 * in the wrong place.</p>
 */
@RequiredArgsConstructor
@Slf4j
public class StripeRefundGateway implements RefundGateway {

    private final StripeClient stripe;

    @Override
    public IssuedRefund create(RefundCreateParams params, String idempotencyKey) {
        try {
            Refund refund = stripe.refunds().create(params,
                    RequestOptions.builder().setIdempotencyKey(idempotencyKey).build());
            log.info("Stripe refunded {} {} [refund={}, status={}, reversal={}]",
                    refund.getAmount(), refund.getCurrency(), refund.getId(), refund.getStatus(),
                    refund.getTransferReversal());
            return new IssuedRefund(
                    refund.getId(),
                    // Defensive: a refund Stripe accepted always states an amount, and a null here
                    // would otherwise reach a NOT NULL column as a zero-money refund.
                    refund.getAmount() == null ? 0L : refund.getAmount(),
                    refund.getCurrency(),
                    refund.getStatus(),
                    refund.getReason());
        } catch (StripeException e) {
            // Deliberately not swallowed. Every caller of this is about to record that money went
            // back, and the one thing worse than a failed refund is a row saying it succeeded.
            throw new StripeOperationException(
                    "Could not create a Stripe refund for %s".formatted(idempotencyKey), e);
        }
    }
}
