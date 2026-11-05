package com.confluo.stripe;

import com.stripe.param.TransferReversalCreateParams;

/**
 * Taking a transfer back off a seller's Stripe balance.
 *
 * <p>The third seam of this shape, alongside {@link CheckoutSessionGateway} and
 * {@link RefundGateway}, and narrow for the same reason: the parameters stay Stripe's own type
 * because what goes into a reversal - how much, and whether the platform's cut goes with it - is
 * where the decision lives, and the seam exists so that the deciding can be exercised without
 * moving real money.</p>
 *
 * <p>Create only. A reversal cannot be undone, and what Stripe does about it afterwards arrives on
 * {@code transfer.reversed}, which is what writes the {@code REVERSAL} row - so there is nothing
 * here to read back.</p>
 */
public interface TransferReversalGateway {

    /**
     * Claws money back off a connected account's balance.
     *
     * <p><strong>This can fail for want of balance</strong>, and that is not an exceptional case:
     * once a seller's money has been paid out to their bank there is nothing left to reverse. The
     * caller is expected to treat a failure as "nothing was recovered" rather than as an error to
     * retry forever.</p>
     *
     * @param stripeTransferId Stripe's {@code tr_} identifier for the transfer being reversed
     * @param params how much, and what happens to the application fee
     * @param idempotencyKey this service's own key, so a redelivered webhook does not claw back
     *                       twice
     * @return what Stripe reversed
     * @throws StripeOperationException if Stripe refused, including for insufficient balance
     */
    Reversal create(String stripeTransferId, TransferReversalCreateParams params, String idempotencyKey);

    /**
     * What Stripe took back.
     *
     * @param id Stripe's {@code trr_} identifier
     * @param amount how much was actually recovered, in minor units - Stripe's figure rather than
     *               the one asked for, which is the only number worth recording
     */
    record Reversal(String id, long amount) {
    }
}
