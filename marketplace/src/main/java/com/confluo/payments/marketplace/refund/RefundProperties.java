package com.confluo.payments.marketplace.refund;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Who bears a refund, which under destination charges is a choice rather than a consequence.
 *
 * <p>The platform is the merchant of record, so the money goes back from the platform's Stripe
 * balance whatever these say. What they decide is whether the seller and the platform give up
 * their shares of a sale that has been undone, or whether the platform absorbs it.</p>
 *
 * <p>Both default to true, which is the arrangement where a cancelled sale leaves nobody better
 * off than if it had never happened. They are settings rather than constants because the
 * alternative is a real commercial position - a marketplace that guarantees sellers their
 * money once a sale completes, and eats refunds as a cost of doing business - and that is not a
 * decision to bury in a method body.</p>
 *
 * @param reverseTransfer whether the seller's share is taken back off their Stripe balance.
 *                        True means a refunded sale costs the seller what they were paid for
 *                        it. Worth knowing: it can <strong>partly</strong> fail, because a
 *                        payout may already have moved the money out of their balance - the
 *                        platform absorbs whatever could not be recovered, and
 *                        {@code Refund.reversedTransferAmount} is what records how much that
 *                        was. False means the seller keeps it and the platform pays the whole
 *                        refund out of its own funds
 * @param refundApplicationFee whether the platform gives up its cut. True means the platform
 *                             keeps nothing from a sale that was undone. False means it keeps
 *                             its fee, which on a full refund makes the platform's margin come
 *                             out of the seller's share - legitimate for a marketplace charging
 *                             for the act of selling rather than the sale, and surprising to
 *                             everybody else. Stripe refuses this with
 *                             {@code reverseTransfer} false, since there is no transfer to take
 *                             the fee out of
 */
@Validated
@ConfigurationProperties(prefix = "confluo.payments.refund")
public record RefundProperties(
        @DefaultValue("true") boolean reverseTransfer,
        @DefaultValue("true") boolean refundApplicationFee) {
}
