package com.confluo.payments.payouts;

import com.confluo.payments.connect.ConnectedAccount;
import com.confluo.payments.connect.ConnectedAccountRepository;
import com.confluo.webhook.StripeEvent;
import com.confluo.webhook.StripeEventHandler;
import com.confluo.payments.marketplace.PaymentRepository;
import com.stripe.model.Event;
import com.stripe.model.StripeObject;
import com.stripe.model.Transfer;
import lombok.extern.slf4j.Slf4j;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;


/**
 * A sale's share has moved onto a seller's Stripe balance.
 *
 * <p>The first of the two movements that get money to a seller, and the one that happens
 * immediately: under destination charges Stripe creates a transfer alongside the charge, moving the
 * seller's share off the platform's balance and onto theirs. The second movement - that balance
 * reaching their bank - is a payout, days later, and is {@link PayoutMirroringHandler}'s.</p>
 *
 * <p>These events arrive on the <strong>platform</strong> endpoint, because the platform is what
 * creates a transfer. Payout events arrive on the Connect endpoint instead, because they happen on
 * the seller's own account. Getting that the wrong way round means registering handlers that never
 * fire.</p>
 *
 * <h2>Finding the sale</h2>
 *
 * <p>{@code sourceTransaction} is a <strong>charge</strong>, and Stripe offers no payment intent on
 * a transfer - which is the entire reason {@code Payment.stripeChargeId} exists. Without it a
 * seller's money could be seen arriving and never attributed to what they sold.</p>
 */
@Slf4j
public class TransferMirroringHandler implements StripeEventHandler {

    private final String eventType;
    private final PayoutTransferRepository movements;
    private final ConnectedAccountRepository accounts;
    private final PaymentRepository payments;

    /**
     * @param eventType which Stripe event this bean handles
     * @param movements the mirrored transfers, payouts and reversals
     * @param accounts the connected accounts, to attach the movement to one
     * @param payments the mirrored payments, to attach a transfer to its sale
     */
    public TransferMirroringHandler(
            String eventType,
            PayoutTransferRepository movements,
            ConnectedAccountRepository accounts,
            PaymentRepository payments) {
        this.eventType = eventType;
        this.movements = movements;
        this.accounts = accounts;
        this.payments = payments;
    }

    @Override
    public String eventType() {
        return eventType;
    }

    @Override
    public void handle(StripeEvent ledgerRow, Event event) {
        Transfer transfer = transferOf(event);

        Optional<ConnectedAccount> account = accounts.findByStripeAccountId(transfer.getDestination());
        if (account.isEmpty()) {
            log.warn("Stripe reported a transfer to account {}, which this service does not have; "
                            + "{} {} cannot be attributed",
                    transfer.getDestination(), transfer.getAmount(), transfer.getCurrency());
            return;
        }

        // A reversal is how the platform claws a seller's share back after a refund or a lost
        // dispute. Stripe does not give a reversal its own event, so the transfer's own id
        // identifies it here - distinct from the transfer row because the uniqueness is per kind.
        boolean reversal = Boolean.TRUE.equals(transfer.getReversed())
                || (transfer.getAmountReversed() != null && transfer.getAmountReversed() > 0);
        PayoutTransferKind kind = reversal
                ? PayoutTransferKind.REVERSAL
                : PayoutTransferKind.TRANSFER;

        long amount = reversal && transfer.getAmountReversed() != null
                ? transfer.getAmountReversed()
                : orZero(transfer.getAmount());
        if (amount <= 0) {
            // ck_payout_transfers_amount refuses it, and rightly: a movement of nothing is not a
            // movement. Nothing is lost by skipping it.
            log.warn("Stripe reported a transfer of {} for account {}; nothing to record",
                    amount, transfer.getDestination());
            return;
        }

        PayoutTransfer movement = movements
                .findByKindAndStripeObjectId(kind, transfer.getId())
                .orElseGet(PayoutTransfer::new);

        movement.setConnectedAccountId(account.get().getId());
        movement.setKind(kind);
        movement.setStripeObjectId(transfer.getId());
        movement.setAmount(amount);
        if (transfer.getCurrency() != null) {
            movement.setCurrency(transfer.getCurrency().toUpperCase(Locale.ROOT));
        }
        movement.setSourcePaymentId(saleOf(transfer));
        // A transfer has no status: it either happened or the API call failed.
        movement.setStatus(null);

        PayoutTransfer saved = movements.save(movement);
        log.info("Transfer mirrored [id={}, kind={}, account={}, amount={} {}, sale={}]",
                saved.getId(), kind, account.get().getFirebaseUid(),
                saved.getAmount(), saved.getCurrency(),
                saved.getSourcePaymentId() == null ? "unattributed" : saved.getSourcePaymentId());
    }

    /**
     * The sale a transfer came from, if it can be found.
     *
     * <p>Null is a real outcome rather than a failure: a transfer the platform made by hand, or one
     * for a charge whose {@code stripeChargeId} was never recorded. The movement is still a fact
     * about the seller's balance and belongs in their history either way.</p>
     *
     * @param transfer what Stripe sent
     * @return the payment's id, or null
     */
    private UUID saleOf(Transfer transfer) {
        String chargeId = transfer.getSourceTransaction();
        if (chargeId == null) {
            return null;
        }
        return payments.findByStripeChargeId(chargeId)
                .map(payment -> payment.getId())
                .orElseGet(() -> {
                    log.info("Transfer {} came from charge {}, which this service cannot match to "
                            + "a payment", transfer.getId(), chargeId);
                    return null;
                });
    }

    private static long orZero(Long value) {
        return value == null ? 0L : value;
    }

    private Transfer transferOf(Event event) {
        Optional<StripeObject> deserialized = event.getDataObjectDeserializer().getObject();
        StripeObject object = deserialized.orElseGet(() -> {
            log.warn("Stripe event {} is at API version {} but this service pins {}; "
                            + "deserialising it anyway",
                    event.getId(), event.getApiVersion(), com.stripe.Stripe.API_VERSION);
            try {
                return event.getDataObjectDeserializer().deserializeUnsafe();
            } catch (Exception e) {
                throw new IllegalStateException(
                        "Could not read the data object of Stripe event %s".formatted(event.getId()), e);
            }
        });
        if (!(object instanceof Transfer transfer)) {
            throw new IllegalStateException(
                    "Stripe event %s (%s) carries a %s, not a Transfer".formatted(
                            event.getId(), event.getType(), object.getClass().getSimpleName()));
        }
        return transfer;
    }
}
