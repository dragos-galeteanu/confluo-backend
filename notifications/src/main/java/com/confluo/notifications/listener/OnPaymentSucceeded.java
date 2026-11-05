package com.confluo.notifications.listener;

import com.confluo.amqp.consume.EventHandler;
import com.confluo.amqp.event.EventEnvelope;
import com.confluo.amqp.event.payments.PaymentSucceededEvent;
import com.confluo.notifications.service.NotificationService;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Sends a receipt when a one-off purchase is paid for.
 *
 * <p>The counterpart of {@link OnInvoicePaid}, and they do not overlap: a subscription's money
 * arrives as an invoice and a marketplace sale arrives as a payment. The payments service does not
 * create a payment row for a subscription charge, so no {@code payment.succeeded} is published for
 * one - which is what keeps a subscriber from getting two receipts for the same renewal.</p>
 *
 * <p>No invoice document to link to here, because there is none: Stripe raises invoices for
 * subscriptions, not for one-off charges. So this template has to state the amount itself, which is
 * why the event carries it.</p>
 */
@Component
public class OnPaymentSucceeded extends PaymentEventNotifier
        implements EventHandler<PaymentSucceededEvent> {

    /** The template this sends. TRANSACTIONAL: a receipt for money taken is not optional. */
    static final String TEMPLATE = "payment.receipt";

    /**
     * @param notifications the send pipeline
     */
    public OnPaymentSucceeded(NotificationService notifications) {
        super(notifications);
    }

    @Override
    public String producer() {
        return PRODUCER;
    }

    @Override
    public void handle(EventEnvelope<PaymentSucceededEvent> envelope) {
        PaymentSucceededEvent event = envelope.payload();

        Map<String, Object> variables = new HashMap<>();
        variables.put("amount", event.amount());
        variables.put("currency", event.currency());
        // What was bought, as the service that owns orders names it. The template can use it to
        // say more than "a payment", without this service knowing what an inspection is.
        variables.put("orderReferenceType", event.orderReferenceType());
        variables.put("orderReferenceId", event.orderReferenceId());

        send(envelope, event.firebaseUid(), TEMPLATE,
                "receipt:payment:" + event.paymentId(), variables);
    }
}
