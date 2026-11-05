package com.confluo.payments.payouts.dispute;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Who bears a lost chargeback.
 *
 * <p>Under destination charges Stripe debits the <strong>platform</strong> for a dispute, because
 * the platform is the merchant of record - the seller was paid when the sale completed and their
 * balance is untouched. Recovering their share is a transfer reversal the platform has to decide
 * to make, and these say whether it makes it.</p>
 *
 * <p>Reversing is the default, chosen deliberately over holding a reserve against every seller:
 * a reserve withholds money from everybody who has done nothing wrong, to cover the few who
 * attract chargebacks. The cost of the choice is that a reversal can find nothing left - once a
 * seller's balance has reached their bank there is nothing to take back - and the platform
 * absorbs whatever could not be recovered. {@code Dispute.transferReversedAmount} is what records
 * which happened.</p>
 *
 * @param reverseTransfer whether the seller's share is clawed back when a dispute is lost. False
 *                        means the platform absorbs every chargeback itself, which is a real
 *                        position for a marketplace that guarantees its sellers their money
 * @param refundApplicationFee whether the platform also gives up its cut of the disputed sale.
 *                             <strong>False</strong>, unlike the refund setting, and the
 *                             asymmetry is the point: a refund is a sale unwound by agreement,
 *                             so nobody should profit from it, while a lost dispute is a loss
 *                             the platform is already bearing along with Stripe's dispute fee.
 *                             True here would return the commission to the seller, leaving them
 *                             better off for a chargeback than for a completed sale
 */
@Validated
@ConfigurationProperties(prefix = "confluo.payments.dispute")
public record DisputeProperties(
        @DefaultValue("true") boolean reverseTransfer,
        @DefaultValue("false") boolean refundApplicationFee) {
}
