package com.confluo.payments.marketplace.refund;

import com.confluo.amqp.event.payments.RefundIssuedEvent;
import com.confluo.amqp.publish.EventPublisher;
import com.confluo.webhook.StripeEvent;
import com.confluo.payments.marketplace.Payment;
import com.confluo.payments.marketplace.PaymentMirroringHandler;
import com.confluo.payments.marketplace.PaymentRepository;
import lombok.extern.slf4j.Slf4j;

import java.util.Locale;
import java.util.Optional;


/**
 * What the two refund handlers share: turning one Stripe refund into a row, whether this service
 * has seen it before or not.
 *
 * <p>Both cases are ordinary. A refund this service issued already has a row, written with the real
 * {@link RefundInitiator} by whoever asked for it, and the webhook only moves its status. A refund
 * made in Stripe's dashboard has no row at all, and this is where it comes into existence - as
 * {@link RefundInitiator#STRIPE_DASHBOARD}, which is true, rather than as {@code SUPPORT}, which
 * would claim an API call that was never made.</p>
 *
 * <p>{@link RefundInitiator} is set on insert and <strong>never</strong> on update, for that
 * reason: the webhook does not know who asked, and overwriting what the row already says would turn
 * every refund this service issued into a dashboard refund the moment Stripe mentioned it again.</p>
 */
@Slf4j
public abstract class RefundMirroringHandler extends PaymentMirroringHandler {

    protected final RefundRepository refunds;

    /**
     * @param payments the mirrored payments
     * @param refunds the mirrored refunds
     * @param events where a refund is announced
     */
    protected RefundMirroringHandler(
            PaymentRepository payments, RefundRepository refunds, EventPublisher events) {
        super(payments, events);
        this.refunds = refunds;
    }

    /**
     * Writes one Stripe refund into this service's own row.
     *
     * @param ledgerRow the event being applied
     * @param payment the payment it came off
     * @param stripeRefund the refund as Stripe states it
     * @return the row and whether it has just succeeded, or empty if this event is older than what
     *         the row already holds
     */
    protected Optional<Mirrored> mirror(
            StripeEvent ledgerRow, Payment payment, com.stripe.model.Refund stripeRefund) {

        Optional<Refund> existing = refunds.findByStripeRefundId(stripeRefund.getId());

        if (existing.isPresent() && isStale(ledgerRow, existing.get().getLastStripeEventAt())) {
            log.info("Ignoring Stripe event {} for refund {}: it occurred at {}, and {} has "
                            + "already been applied",
                    ledgerRow.getStripeEventId(), stripeRefund.getId(),
                    ledgerRow.getEventCreatedAt(), existing.get().getLastStripeEventAt());
            return Optional.empty();
        }

        Refund refund = existing.orElseGet(() -> {
            Refund created = new Refund();
            created.setPayment(payment);
            created.setStripeRefundId(stripeRefund.getId());
            // Only ever set here. See the class javadoc.
            created.setInitiatedBy(RefundInitiator.STRIPE_DASHBOARD);
            log.info("Refund {} was not issued through this service; recording it as {}",
                    stripeRefund.getId(), RefundInitiator.STRIPE_DASHBOARD);
            return created;
        });

        if (stripeRefund.getAmount() != null) {
            refund.setAmount(stripeRefund.getAmount());
        }
        if (stripeRefund.getCurrency() != null) {
            // Stripe speaks lowercase; the column is constrained to upper, and the fold happens
            // here because this is the boundary.
            refund.setCurrency(stripeRefund.getCurrency().toUpperCase(Locale.ROOT));
        }
        refund.setReason(stripeRefund.getReason());

        RefundStatus previous = existing.map(Refund::getStatus).orElse(null);
        refund.setStatus(RefundStatus.of(stripeRefund.getStatus()));
        refund.setLastStripeEventAt(ledgerRow.getEventCreatedAt());

        boolean becameSucceeded = refund.getStatus() == RefundStatus.SUCCEEDED
                && previous != RefundStatus.SUCCEEDED;

        return Optional.of(new Mirrored(refunds.save(refund), becameSucceeded));
    }

    /**
     * Announces a refund that has just succeeded.
     *
     * <p>Called by the handler rather than by {@link #mirror}, and that is not arbitrary: the event
     * carries whether the <strong>payment</strong> is now fully refunded, and
     * {@code ChargeRefundedHandler} does not know that until it has written the payment's new
     * refunded total. Announcing from inside the mirror would read a stale total and tell consumers
     * an order was partially refunded when it was returned in full.</p>
     *
     * @param mirrored the refund, as mirrored
     * @param payment the payment it came off, with its refunded total current
     */
    protected void announce(Mirrored mirrored, Payment payment) {
        if (!mirrored.becameSucceeded()) {
            return;
        }
        Refund refund = mirrored.refund();
        java.util.UUID eventId = events.publish(refund.getId(), new RefundIssuedEvent(
                refund.getId(),
                payment.getId(),
                payment.getFirebaseUid(),
                refund.getAmount(),
                refund.getCurrency(),
                payment.getAmountRefunded() >= payment.getAmount(),
                payment.getOrderReferenceType(),
                payment.getOrderReferenceId()));
        log.info("Announced refund.issued [refund={}, payment={}, event={}]",
                refund.getId(), payment.getId(), eventId);
    }

    /**
     * What came of mirroring one refund.
     *
     * @param refund the row
     * @param becameSucceeded whether it reached succeeded on this event rather than earlier, which
     *                        is what decides whether anything is announced
     */
    protected record Mirrored(Refund refund, boolean becameSucceeded) {
    }
}
