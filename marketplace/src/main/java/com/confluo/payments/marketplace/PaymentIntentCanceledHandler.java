package com.confluo.payments.marketplace;

import com.confluo.amqp.publish.EventPublisher;
import com.confluo.webhook.StripeEvent;
import com.stripe.model.Event;
import com.stripe.model.PaymentIntent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * The payment was abandoned for good.
 *
 * <p>Distinct from a failure: nothing was refused, the intent was withdrawn - by Stripe when a
 * session's window closed, or by this platform cancelling an order before it was paid. A payment
 * that failed might still be retried by the payer; one that is cancelled will not be.</p>
 */
@Component
@Slf4j
public class PaymentIntentCanceledHandler extends PaymentMirroringHandler {

    /**
     * @param payments the mirrored payments
     * @param events where a change of state is announced
     */
    public PaymentIntentCanceledHandler(PaymentRepository payments, EventPublisher events) {
        super(payments, events);
    }

    @Override
    public String eventType() {
        return "payment_intent.canceled";
    }

    @Override
    public void handle(StripeEvent ledgerRow, Event event) {
        PaymentIntent intent = objectOf(event, PaymentIntent.class);

        byIntent(ledgerRow, intent.getId()).ifPresent(payment -> {
            PaymentStatus before = payment.getStatus();
            if (payment.getStatus() == PaymentStatus.SUCCEEDED) {
                // Cancelling a paid payment is not something to record quietly. It should not
                // happen - Stripe cannot cancel a succeeded intent - so if it is seen, the mapping
                // between this row and that intent is wrong, and refunding is the only way back.
                log.error("Stripe cancelled intent {} but payment {} is already SUCCEEDED; "
                                + "leaving it alone and not cancelling",
                        intent.getId(), payment.getId());
                return;
            }
            payment.setStatus(PaymentStatus.CANCELED);
            save(payment, ledgerRow);
            announce(payment, before);
            log.info("Payment canceled [payment={}, intent={}]", payment.getId(), intent.getId());
        });
    }
}
