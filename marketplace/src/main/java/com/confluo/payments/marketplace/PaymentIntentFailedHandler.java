package com.confluo.payments.marketplace;

import com.confluo.amqp.publish.EventPublisher;
import com.confluo.webhook.StripeEvent;
import com.stripe.model.Event;
import com.stripe.model.PaymentIntent;
import com.stripe.model.StripeError;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * The payer's method was refused.
 *
 * <p>Not necessarily final. Stripe keeps the intent usable, so a payer who was declined can try
 * another card on the same session and succeed - which is why this records the reason but does not
 * close anything, and why {@code PaymentIntentSucceededHandler} clears the reason rather than
 * refusing to overwrite a failure.</p>
 *
 * <p>The code and the message are both kept, and they are not the same thing: the code is what
 * anything automated should branch on, and the message is Stripe's own wording of it, which is the
 * only text fit to put in front of a person.</p>
 */
@Component
@Slf4j
public class PaymentIntentFailedHandler extends PaymentMirroringHandler {

    /**
     * @param payments the mirrored payments
     * @param events where a change of state is announced
     */
    public PaymentIntentFailedHandler(PaymentRepository payments, EventPublisher events) {
        super(payments, events);
    }

    @Override
    public String eventType() {
        return "payment_intent.payment_failed";
    }

    @Override
    public void handle(StripeEvent ledgerRow, Event event) {
        PaymentIntent intent = objectOf(event, PaymentIntent.class);

        byIntent(ledgerRow, intent.getId()).ifPresent(payment -> {
            PaymentStatus before = payment.getStatus();
            payment.setStatus(PaymentStatus.FAILED);
            StripeError error = intent.getLastPaymentError();
            payment.setFailureCode(error == null ? null : error.getCode());
            payment.setFailureMessage(error == null ? null : error.getMessage());

            save(payment, ledgerRow);
            announce(payment, before);
            log.info("Payment failed [payment={}, intent={}, code={}]",
                    payment.getId(), intent.getId(), payment.getFailureCode());
        });
    }
}
