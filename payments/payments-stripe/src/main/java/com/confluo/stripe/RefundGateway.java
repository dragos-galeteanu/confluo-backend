package com.confluo.stripe;

import com.stripe.param.RefundCreateParams;

/**
 * The one thing this service does to a Stripe refund: create it.
 *
 * <p>A seam of the same shape as {@link CheckoutSessionGateway}, and narrow for the same reason.
 * The parameters stay Stripe's own type, because what goes into a refund - how much, whether the
 * seller's share comes back, whether the platform gives up its cut - is where every decision in
 * this part of the service lives, and a parallel set of records would hide exactly the part worth
 * reading.</p>
 *
 * <p>There is no retrieve and no cancel. A refund cannot be changed once created, and what happens
 * to it afterwards arrives on a webhook rather than by being asked for - so the only reason to read
 * one back would be to second-guess the handler that mirrors it.</p>
 */
public interface RefundGateway {

    /**
     * Sends money back, or hands back the refund an identical earlier request already made.
     *
     * @param params what to refund and how
     * @param idempotencyKey this service's own key for the attempt, so a retry does not refund
     *                       twice - the single most important argument here, because unlike a
     *                       checkout session a duplicate refund moves real money
     * @return what Stripe created
     */
    IssuedRefund create(RefundCreateParams params, String idempotencyKey);

    /**
     * What Stripe said about the refund it made.
     *
     * <p>Carries the status because the create call already answers it, and a refund is not
     * instantly succeeded: it is usually {@code pending} at this point and the webhook moves it.
     * Taking the status from here rather than assuming one is what stops a row claiming money has
     * gone back before Stripe says it has.</p>
     *
     * @param id Stripe's {@code re_} identifier
     * @param amount how much is going back, in minor units - Stripe's number rather than the one
     *               asked for, which matters for a refund of "everything left"
     * @param currency as Stripe states it, lowercase
     * @param status Stripe's own: {@code pending}, {@code succeeded}, {@code failed} or
     *               {@code canceled}
     * @param reason Stripe's own reason code, or null if none was given
     */
    record IssuedRefund(String id, long amount, String currency, String status, String reason) {
    }
}
