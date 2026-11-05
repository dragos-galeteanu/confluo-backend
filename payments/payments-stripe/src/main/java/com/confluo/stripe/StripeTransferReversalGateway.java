package com.confluo.stripe;

import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.TransferReversal;
import com.stripe.net.RequestOptions;
import com.stripe.param.TransferReversalCreateParams;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * {@link TransferReversalGateway} against Stripe.
 *
 * <p>Nothing but the call, the idempotency key and the mapping. Any decision that appears here is
 * in the wrong place.</p>
 */
@RequiredArgsConstructor
@Slf4j
public class StripeTransferReversalGateway implements TransferReversalGateway {

    private final StripeClient stripe;

    @Override
    public Reversal create(
            String stripeTransferId, TransferReversalCreateParams params, String idempotencyKey) {
        try {
            TransferReversal reversal = stripe.transfers().reversals().create(
                    stripeTransferId, params,
                    RequestOptions.builder().setIdempotencyKey(idempotencyKey).build());
            log.info("Reversed {} of transfer {} [reversal={}]",
                    reversal.getAmount(), stripeTransferId, reversal.getId());
            return new Reversal(
                    reversal.getId(),
                    reversal.getAmount() == null ? 0L : reversal.getAmount());
        } catch (StripeException e) {
            throw new StripeOperationException(
                    "Could not reverse transfer %s for %s".formatted(stripeTransferId, idempotencyKey), e);
        }
    }
}
