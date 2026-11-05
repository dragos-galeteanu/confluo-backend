package com.confluo.payments.payouts.dispute;

import com.confluo.amqp.event.payments.DisputeClosedEvent;
import com.confluo.amqp.event.payments.DisputeOpenedEvent;
import com.confluo.amqp.publish.EventPublisher;
import com.confluo.webhook.StripeEvent;
import com.confluo.webhook.StripeEventHandler;
import com.confluo.payments.marketplace.Payment;
import com.confluo.payments.marketplace.PaymentRepository;
import com.stripe.model.Event;
import com.stripe.model.StripeObject;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;


/**
 * A payer has charged back, or Stripe has settled it.
 *
 * <h2>What a dispute costs this platform</h2>
 *
 * <p>Under destination charges the platform is the <strong>merchant of record</strong>. So when a
 * payer disputes a marketplace sale, Stripe debits the platform's balance, not the seller's - the
 * seller has already been paid. Recovering their share is a transfer reversal this platform has to
 * decide to make, and it now makes it: {@link DisputeClawback} reverses the transfer when a dispute
 * goes to {@code lost}, and {@code transferReversedAmount} records how much actually came back,
 * which is nothing if the seller's balance has already reached their bank.</p>
 *
 * <p>The clawback runs <strong>before</strong> the row is saved, so the amount recovered is on the
 * dispute and in {@code dispute.closed} rather than arriving in a later event: a consumer told a
 * dispute was lost wants to know whether the loss landed on the platform or on the seller.</p>
 *
 * <p>It also never throws, and that shapes this handler. The dispute has a <strong>deadline</strong>
 * - miss it and it is lost by default - so a failed clawback must not roll back the record of the
 * dispute that caused it. {@link DisputeClawback} returns zero and logs instead.</p>
 *
 * <h2>One class, three event types</h2>
 *
 * <p>As with invoices: created, updated and closed all say "here is the dispute as it stands", and
 * the status comes off the dispute rather than from which event carried it.
 * {@code DisputeHandlerConfiguration} registers one bean per type.</p>
 *
 * <p>{@code funds_withdrawn} and {@code funds_reinstated} are deliberately not among them. They
 * report Stripe moving money on its own balance, which this service has no column for and cannot
 * act on; the dispute's status already says where things stand.</p>
 */
@Slf4j
public class DisputeMirroringHandler implements StripeEventHandler {

    /** Stripe's statuses that mean the dispute is settled and the money has stopped moving. */
    private static final Set<String> CLOSED = Set.of("won", "lost", "warning_closed");

    /** The one of those that means the platform kept the money. */
    private static final String WON = "won";

    private final String eventType;
    private final DisputeRepository disputes;
    private final PaymentRepository payments;
    private final EventPublisher events;
    private final DisputeClawback clawback;

    /**
     * @param eventType which Stripe event this bean handles
     * @param disputes the mirrored disputes
     * @param payments the mirrored payments, to attach the dispute to one
     * @param events where a dispute is announced
     * @param clawback what takes the seller's share back when the dispute is lost
     */
    public DisputeMirroringHandler(
            String eventType,
            DisputeRepository disputes,
            PaymentRepository payments,
            EventPublisher events,
            DisputeClawback clawback) {
        this.eventType = eventType;
        this.disputes = disputes;
        this.payments = payments;
        this.events = events;
        this.clawback = clawback;
    }

    @Override
    public String eventType() {
        return eventType;
    }

    @Override
    public void handle(StripeEvent ledgerRow, Event event) {
        com.stripe.model.Dispute stripeDispute = disputeOf(event);

        Optional<Dispute> existing = disputes.findByStripeDisputeId(stripeDispute.getId());
        if (existing.isPresent() && isStale(ledgerRow, existing.get().getLastStripeEventAt())) {
            log.info("Ignoring Stripe event {} for dispute {}: it occurred at {}, and {} has "
                            + "already been applied",
                    ledgerRow.getStripeEventId(), stripeDispute.getId(),
                    ledgerRow.getEventCreatedAt(), existing.get().getLastStripeEventAt());
            return;
        }

        Payment payment;
        if (existing.isPresent()) {
            // Loaded by id rather than walked through an association: see Dispute#paymentId. The
            // row cannot be missing while the foreign key stands, so this is an invariant check
            // and not a path anything takes.
            UUID paymentId = existing.get().getPaymentId();
            payment = payments.findById(paymentId)
                    .orElseThrow(() -> new IllegalStateException(
                            "Dispute %s names payment %s, which this service no longer has"
                                    .formatted(existing.get().getStripeDisputeId(), paymentId)));
        } else {
            // A dispute names its payment intent, which is the one link Stripe gives that this
            // service can follow directly - unlike a transfer, which names only a charge.
            Optional<Payment> found = payments
                    .findByStripePaymentIntentId(stripeDispute.getPaymentIntent());
            if (found.isEmpty()) {
                // Retrying cannot conjure a payment, so the event is applied as a no-op - but
                // loudly, because a chargeback against a charge this platform cannot identify is
                // money leaving with nothing to attribute it to.
                log.error("Stripe reported a dispute on payment intent {}, which this service does "
                                + "not have; {} of {} is being claimed back and cannot be attributed",
                        stripeDispute.getPaymentIntent(), stripeDispute.getAmount(),
                        stripeDispute.getCurrency());
                return;
            }
            payment = found.get();
        }

        Dispute dispute = existing.orElseGet(Dispute::new);
        // Read before the setters below: existing.get() and dispute are the same object.
        String before = existing.map(Dispute::getStatus).orElse(null);

        if (existing.isEmpty()) {
            dispute.setPaymentId(payment.getId());
            dispute.setStripeDisputeId(stripeDispute.getId());
        }
        if (stripeDispute.getAmount() != null) {
            dispute.setAmount(stripeDispute.getAmount());
        }
        if (stripeDispute.getCurrency() != null) {
            dispute.setCurrency(stripeDispute.getCurrency().toUpperCase(Locale.ROOT));
        }
        dispute.setReason(stripeDispute.getReason());
        dispute.setStatus(stripeDispute.getStatus());
        dispute.setChargeRefundable(Boolean.TRUE.equals(stripeDispute.getIsChargeRefundable()));
        dispute.setEvidenceDueBy(evidenceDueBy(stripeDispute));
        dispute.setLastStripeEventAt(ledgerRow.getEventCreatedAt());

        // Here rather than after the save, so the amount recovered is written on the row and
        // carried by dispute.closed. Zero for anything that is not a lost dispute with something
        // left to take, and it never throws - losing the record of a dispute because a clawback
        // failed would be the expensive way to handle the cheap problem.
        long recovered = clawback.reverseFor(dispute, payment);
        if (recovered > 0) {
            dispute.setTransferReversedAmount(dispute.getTransferReversedAmount() + recovered);
        }

        Dispute saved = disputes.save(dispute);
        announce(saved, payment, before);

        log.warn("Dispute mirrored [dispute={}, payment={}, status={}, amount={} {}, dueBy={}]",
                saved.getId(), payment.getId(), saved.getStatus(),
                saved.getAmount(), saved.getCurrency(), saved.getEvidenceDueBy());
    }

    /**
     * Announces a dispute opening or settling.
     *
     * <p>On the transition, as everywhere else. {@code charge.dispute.updated} fires for evidence
     * being submitted and deadlines shifting, and neither is a new dispute.</p>
     *
     * @param dispute the dispute as it now stands
     * @param payment what is being disputed
     * @param before its Stripe status before this event, or null if it is new here
     */
    private void announce(Dispute dispute, Payment payment, String before) {
        String seller = payment.getSellerFirebaseUid();

        if (before == null) {
            UUID eventId = events.publish(dispute.getId(), new DisputeOpenedEvent(
                    dispute.getId(),
                    payment.getId(),
                    payment.getFirebaseUid(),
                    seller,
                    dispute.getAmount(),
                    dispute.getCurrency(),
                    dispute.getReason(),
                    dispute.getEvidenceDueBy(),
                    payment.getOrderReferenceType(),
                    payment.getOrderReferenceId()));
            log.info("Announced dispute.opened [dispute={}, event={}]", dispute.getId(), eventId);
            return;
        }

        if (CLOSED.contains(dispute.getStatus()) && !CLOSED.contains(before)) {
            UUID eventId = events.publish(dispute.getId(), new DisputeClosedEvent(
                    dispute.getId(),
                    payment.getId(),
                    payment.getFirebaseUid(),
                    seller,
                    dispute.getAmount(),
                    dispute.getCurrency(),
                    WON.equals(dispute.getStatus()),
                    dispute.getTransferReversedAmount()));
            log.info("Announced dispute.closed [dispute={}, status={}, event={}]",
                    dispute.getId(), dispute.getStatus(), eventId);
        }
    }

    /**
     * When evidence is due, if Stripe has set a deadline.
     *
     * <p>Nested inside {@code evidence_details}, which is absent on a dispute Stripe is not asking
     * anything about - a warning, or one already settled.</p>
     *
     * @param stripeDispute what Stripe sent
     * @return the deadline, or null
     */
    private Instant evidenceDueBy(com.stripe.model.Dispute stripeDispute) {
        if (stripeDispute.getEvidenceDetails() == null
                || stripeDispute.getEvidenceDetails().getDueBy() == null) {
            return null;
        }
        return Instant.ofEpochSecond(stripeDispute.getEvidenceDetails().getDueBy());
    }

    private boolean isStale(StripeEvent ledgerRow, Instant lastApplied) {
        return lastApplied != null
                && ledgerRow.getEventCreatedAt() != null
                && !ledgerRow.getEventCreatedAt().isAfter(lastApplied);
    }

    private com.stripe.model.Dispute disputeOf(Event event) {
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
        if (!(object instanceof com.stripe.model.Dispute dispute)) {
            throw new IllegalStateException(
                    "Stripe event %s (%s) carries a %s, not a Dispute".formatted(
                            event.getId(), event.getType(), object.getClass().getSimpleName()));
        }
        return dispute;
    }
}
