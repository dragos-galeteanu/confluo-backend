package com.confluo.payments.marketplace.refund;

import com.confluo.amqp.publish.EventPublisher;
import com.confluo.webhook.StripeEvent;
import com.confluo.payments.marketplace.PaymentRepository;
import com.stripe.model.Event;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * A refund changed state after it was created.
 *
 * <p>Needed as well as {@code charge.refunded}, not instead of it. That event fires when a refund
 * is made, while this one fires when it finishes - and the gap between them is real: a refund is
 * submitted to the payer's bank and can take days, or come back
 * {@link RefundStatus#FAILED} because the card or account has closed since the charge. Without this
 * handler every refund would stay at whatever status it had the moment it was created, and a
 * failed one would read as money returned.</p>
 *
 * <p>It creates the row if there is none, which is not the usual case but is reachable: an
 * unarchived {@code charge.refunded} that exhausted its attempts, or a refund whose first event
 * was never delivered.</p>
 */
@Component
@Slf4j
public class RefundUpdatedHandler extends RefundMirroringHandler {

    /**
     * @param payments the mirrored payments
     * @param refunds the mirrored refunds
     * @param events where a refund is announced
     */
    public RefundUpdatedHandler(
            PaymentRepository payments, RefundRepository refunds, EventPublisher events) {
        super(payments, refunds, events);
    }

    @Override
    public String eventType() {
        return "refund.updated";
    }

    @Override
    public void handle(StripeEvent ledgerRow, Event event) {
        com.stripe.model.Refund stripeRefund = objectOf(event, com.stripe.model.Refund.class);

        locateByIntent(ledgerRow, stripeRefund.getPaymentIntent()).ifPresent(payment ->
                mirror(ledgerRow, payment, stripeRefund).ifPresent(mirrored -> {
                    // The payment's refunded total is whatever charge.refunded last wrote, which is
                    // current: this event changes a refund's status, never an amount.
                    announce(mirrored, payment);
                    log.info("Refund updated [refund={}, stripeRefund={}, status={}]",
                            mirrored.refund().getId(), stripeRefund.getId(),
                            mirrored.refund().getStatus());
                }));
    }
}
