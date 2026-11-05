package com.confluo.payments.payouts;

import com.confluo.payments.connect.ConnectedAccount;
import com.confluo.payments.connect.ConnectedAccountRepository;
import com.confluo.webhook.StripeEvent;
import com.confluo.webhook.StripeEventHandler;
import com.stripe.model.Event;
import com.stripe.model.Payout;
import com.stripe.model.StripeObject;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;


/**
 * Money is on its way from a seller's Stripe balance to their bank.
 *
 * <p>The second of the two movements, and the one a seller actually asks about: "when will it be in
 * my account". {@code arrivalDate} is the answer and is the reason this handler exists - it is
 * Stripe's estimate, days ahead of the payout being created, and nothing else in this platform
 * knows it.</p>
 *
 * <h2>These arrive on the Connect endpoint</h2>
 *
 * <p>A payout happens on the <strong>seller's</strong> account, so the event comes to the
 * connected-accounts endpoint and the {@code Payout} object itself names nobody: it has a
 * destination, but that is a bank account, not an account holder. Whose payout it is comes from the
 * event's own {@code account} field, which the webhook ledger recorded as
 * {@code stripeAccountId} - which is the only reason that column is worth having.</p>
 *
 * <p>A payout aggregates whatever was on the balance, so it corresponds to no single sale and
 * {@code sourcePaymentId} is always null. That is not a gap: the transfers are where individual
 * sales are attributed.</p>
 */
@Slf4j
public class PayoutMirroringHandler implements StripeEventHandler {

    private final String eventType;
    private final PayoutTransferRepository movements;
    private final ConnectedAccountRepository accounts;

    /**
     * @param eventType which Stripe event this bean handles
     * @param movements the mirrored transfers, payouts and reversals
     * @param accounts the connected accounts, to attach the payout to one
     */
    public PayoutMirroringHandler(
            String eventType,
            PayoutTransferRepository movements,
            ConnectedAccountRepository accounts) {
        this.eventType = eventType;
        this.movements = movements;
        this.accounts = accounts;
    }

    @Override
    public String eventType() {
        return eventType;
    }

    @Override
    public void handle(StripeEvent ledgerRow, Event event) {
        Payout payout = payoutOf(event);

        // The Payout says nothing about whose it is. The event does, and the ledger kept it.
        String stripeAccountId = ledgerRow.getStripeAccountId();
        if (stripeAccountId == null) {
            log.warn("Stripe reported payout {} with no account on the event; it cannot be "
                            + "attributed. A payout event should arrive on the Connect endpoint.",
                    payout.getId());
            return;
        }

        Optional<ConnectedAccount> account = accounts.findByStripeAccountId(stripeAccountId);
        if (account.isEmpty()) {
            log.warn("Stripe reported payout {} for account {}, which this service does not have",
                    payout.getId(), stripeAccountId);
            return;
        }

        PayoutTransfer movement = movements
                .findByKindAndStripeObjectId(PayoutTransferKind.PAYOUT, payout.getId())
                .orElseGet(PayoutTransfer::new);

        movement.setConnectedAccountId(account.get().getId());
        movement.setKind(PayoutTransferKind.PAYOUT);
        movement.setStripeObjectId(payout.getId());
        if (payout.getAmount() != null) {
            movement.setAmount(payout.getAmount());
        }
        if (payout.getCurrency() != null) {
            movement.setCurrency(payout.getCurrency().toUpperCase(Locale.ROOT));
        }
        movement.setStatus(payout.getStatus());
        movement.setArrivalDate(payout.getArrivalDate() == null
                ? null
                : Instant.ofEpochSecond(payout.getArrivalDate()));
        movement.setFailureCode(payout.getFailureCode());
        movement.setFailureMessage(payout.getFailureMessage());

        PayoutTransfer saved = movements.save(movement);

        if (payout.getFailureCode() != null) {
            // A failed payout is money that did not arrive, and the seller does not know. It stays
            // on their Stripe balance, so nothing is lost - but their bank details are usually
            // wrong, and only they can fix that.
            log.error("Payout failed [id={}, account={}, amount={} {}, code={}, message={}]",
                    saved.getId(), account.get().getFirebaseUid(), saved.getAmount(),
                    saved.getCurrency(), payout.getFailureCode(), payout.getFailureMessage());
        } else {
            log.info("Payout mirrored [id={}, account={}, amount={} {}, status={}, arrives={}]",
                    saved.getId(), account.get().getFirebaseUid(), saved.getAmount(),
                    saved.getCurrency(), saved.getStatus(), saved.getArrivalDate());
        }
    }

    private Payout payoutOf(Event event) {
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
        if (!(object instanceof Payout payout)) {
            throw new IllegalStateException(
                    "Stripe event %s (%s) carries a %s, not a Payout".formatted(
                            event.getId(), event.getType(), object.getClass().getSimpleName()));
        }
        return payout;
    }
}
