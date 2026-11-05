package com.confluo.payments.payouts.dispute;

import com.confluo.payments.marketplace.Payment;
import com.confluo.payments.payouts.PayoutTransfer;
import com.confluo.payments.payouts.PayoutTransferKind;
import com.confluo.payments.payouts.PayoutTransferRepository;
import com.confluo.stripe.StripeOperationException;
import com.confluo.stripe.TransferReversalGateway;
import com.stripe.param.TransferReversalCreateParams;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;


/**
 * Takes the seller's share back when a chargeback is lost.
 *
 * <h2>Why anything has to be taken back at all</h2>
 *
 * <p>Under destination charges the platform is the <strong>merchant of record</strong>. A payer who
 * charges back is charging back the platform: Stripe debits the platform's balance for the full
 * disputed amount and adds its own dispute fee on top, while the seller's balance is untouched -
 * they were paid when the sale completed. So unless something reverses their transfer, a lost
 * dispute is paid for entirely by the platform for a sale it only brokered.</p>
 *
 * <p>Reversing was chosen over holding a reserve against every seller, which would withhold money
 * from everybody who has done nothing wrong in order to cover the few who attract chargebacks. The
 * price of that choice is that reversing can find nothing: a seller's balance reaches their bank on
 * Stripe's own schedule, and once it has, there is nothing left to take. The platform absorbs
 * whatever could not be recovered, and {@link Dispute#getTransferReversedAmount()} is the number
 * that says which happened.</p>
 *
 * <h2>Failing is not an error</h2>
 *
 * <p>Nothing here throws. Every refusal - no transfer, an empty balance, Stripe saying no - returns
 * zero and logs, and that is deliberate rather than lax. This runs inside the webhook sweep's
 * per-event transaction, so throwing would roll back the <strong>dispute record itself</strong> and
 * leave the event to be retried and eventually parked. A dispute carries a deadline and missing it
 * loses the money by default, so losing the record of one because a clawback failed would be the
 * expensive way to handle the cheap problem.</p>
 *
 * <p>Which makes the log the alert. A failed clawback is logged at error with the transfer, the
 * amount and the dispute, because it is money the platform has lost and a person has to decide what
 * to do about it.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DisputeClawback {

    /** Stripe's status for a dispute the platform lost. The only one worth taking money back for. */
    static final String LOST = "lost";

    private final PayoutTransferRepository movements;
    private final TransferReversalGateway gateway;
    private final DisputeProperties properties;

    /**
     * Claws back whatever can be clawed back for one dispute.
     *
     * <p>Called from inside the dispute handler before the row is saved, so that the amount it
     * returns is written on the dispute and carried by {@code dispute.closed} - a consumer being
     * told a dispute was lost generally wants to know whether the loss landed on the platform or on
     * the seller.</p>
     *
     * <p>Safe to call for any dispute at any status: everything that is not a lost dispute with
     * something still to recover returns zero.</p>
     *
     * @param dispute the dispute as Stripe now states it, not yet saved
     * @param payment what was disputed. Everything read from it is on the row itself - the
     *                account's id and the seller's uid - so this no longer depends on being called
     *                inside the sweep's transaction to initialise an association
     * @return how much was actually recovered, which is zero whenever nothing was
     */
    public long reverseFor(Dispute dispute, Payment payment) {
        if (!LOST.equals(dispute.getStatus())) {
            return 0;
        }
        if (dispute.getTransferReversedAmount() > 0) {
            // Already done, on an earlier delivery of this or another event about the same dispute.
            return 0;
        }
        if (!properties.reverseTransfer()) {
            log.info("Dispute {} was lost and the seller's share is not being clawed back: "
                            + "confluo.payments.dispute.reverse-transfer is off",
                    dispute.getStripeDisputeId());
            return 0;
        }
        if (payment.getConnectedAccountId() == null) {
            // A payment to the platform itself - a subscription invoice. There is no seller and no
            // transfer, so the platform bears it because the platform earned it.
            log.warn("Dispute {} is against payment {}, which has no connected account; there is "
                            + "no transfer to reverse",
                    dispute.getStripeDisputeId(), payment.getId());
            return 0;
        }

        List<PayoutTransfer> transfers = movements
                .findByKindAndSourcePaymentIdOrderByCreatedAtDesc(
                        PayoutTransferKind.TRANSFER, payment.getId());

        if (transfers.isEmpty()) {
            log.error("Dispute {} was lost on payment {} but this service has no transfer recorded "
                            + "against that sale, so {} {} cannot be clawed back from {}",
                    dispute.getStripeDisputeId(), payment.getId(), dispute.getAmount(),
                    dispute.getCurrency(), payment.getSellerFirebaseUid());
            return 0;
        }
        if (transfers.size() > 1) {
            // Refused rather than guessed, as with a cancelled order naming an amount across
            // several payments: picking one of several transfers is a decision about whose money
            // moves, and a person reading this log can make it in the dashboard.
            log.error("Dispute {} was lost on payment {}, which has {} transfers recorded against "
                            + "it ({}); which one to reverse cannot be guessed, so nothing has been "
                            + "clawed back",
                    dispute.getStripeDisputeId(), payment.getId(), transfers.size(),
                    transfers.stream().map(PayoutTransfer::getStripeObjectId).toList());
            return 0;
        }

        PayoutTransfer transfer = transfers.get(0);

        // The seller's share, or the disputed amount, whichever is smaller. Not a proportion: a
        // full chargeback - which is almost all of them - claims more than the seller ever received,
        // because the platform's cut came out of the same charge, so the whole transfer goes back. A
        // partial chargeback takes only what the platform actually lost. Stripe is the real cap and
        // refuses a reversal beyond what is left of the transfer, which is what would happen if a
        // refund had already reversed part of it - that refusal is logged below rather than
        // pre-empted here, because the local rows lag the webhook that writes them.
        long amount = Math.min(transfer.getAmount(), dispute.getAmount());
        if (amount <= 0) {
            log.warn("Dispute {} on payment {} works out to a reversal of {}; nothing to do",
                    dispute.getStripeDisputeId(), payment.getId(), amount);
            return 0;
        }

        TransferReversalCreateParams params = TransferReversalCreateParams.builder()
                .setAmount(amount)
                // False by default, and the asymmetry with a refund is deliberate - see
                // DisputeProperties. True hands the platform's commission back to the
                // seller, leaving them better off for a chargeback than for a completed sale.
                .setRefundApplicationFee(properties.refundApplicationFee())
                .setDescription("Chargeback lost: " + dispute.getStripeDisputeId())
                .putMetadata("dispute_id", dispute.getStripeDisputeId())
                .putMetadata("payment_id", payment.getId().toString())
                .build();

        try {
            TransferReversalGateway.Reversal reversal = gateway.create(
                    transfer.getStripeObjectId(), params,
                    // Keyed by the dispute, so every redelivery of every event about it resolves to
                    // the one reversal. The amount is not in the key and does not need to be: a
                    // dispute is clawed back once.
                    "dispute-reversal:" + dispute.getStripeDisputeId());

            log.warn("Clawed back {} {} from {} for lost dispute {} [transfer={}, reversal={}]",
                    reversal.amount(), dispute.getCurrency(),
                    payment.getSellerFirebaseUid(), dispute.getStripeDisputeId(),
                    transfer.getStripeObjectId(), reversal.id());
            return reversal.amount();
        } catch (StripeOperationException e) {
            // Swallowed on purpose. See the class javadoc: the dispute record matters more than the
            // clawback, and the commonest reason to be here is a seller whose balance has already
            // reached their bank - which no amount of retrying changes.
            log.error("Could not claw back {} {} from {} for lost dispute {} [transfer={}]; the "
                            + "platform is bearing it and the dispute will record nothing recovered",
                    amount, dispute.getCurrency(),
                    payment.getSellerFirebaseUid(), dispute.getStripeDisputeId(),
                    transfer.getStripeObjectId(), e);
            return 0;
        }
    }
}
