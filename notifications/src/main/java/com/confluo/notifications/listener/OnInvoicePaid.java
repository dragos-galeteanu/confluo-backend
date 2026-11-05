package com.confluo.notifications.listener;

import com.confluo.amqp.consume.EventHandler;
import com.confluo.amqp.event.EventEnvelope;
import com.confluo.amqp.event.payments.InvoicePaidEvent;
import com.confluo.notifications.service.NotificationService;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Sends a receipt when a subscription invoice is paid.
 *
 * <p>The template is given Stripe's own {@code hostedInvoiceUrl} and {@code invoicePdf} rather than
 * a reproduction of the invoice. Stripe already produces a correct document - right language, right
 * tax breakdown, kept available - and a receipt that links to it is better than one that tries to
 * restate it and gets the VAT wrong.</p>
 *
 * <p>The deduplication key is {@code receipt:<invoiceId>}: one receipt per invoice, <strong>ever</strong>.
 * That is a stronger rule than the consumed-message ledger gives - the ledger stops the same event
 * being handled twice, while this stops any future event producing a second receipt for the same
 * invoice.</p>
 */
@Component
public class OnInvoicePaid extends PaymentEventNotifier implements EventHandler<InvoicePaidEvent> {

    /** The template this sends. It has to be declared TRANSACTIONAL: a receipt is not optional. */
    static final String TEMPLATE = "invoice.receipt";

    /**
     * @param notifications the send pipeline
     */
    public OnInvoicePaid(NotificationService notifications) {
        super(notifications);
    }

    @Override
    public String producer() {
        return PRODUCER;
    }

    @Override
    public void handle(EventEnvelope<InvoicePaidEvent> envelope) {
        InvoicePaidEvent event = envelope.payload();

        Map<String, Object> variables = new HashMap<>();
        variables.put("invoiceNumber", event.number());
        variables.put("amountPaid", event.amountPaid());
        variables.put("taxAmount", event.taxAmount());
        variables.put("currency", event.currency());
        variables.put("periodStart", event.periodStart());
        variables.put("periodEnd", event.periodEnd());
        variables.put("hostedInvoiceUrl", event.hostedInvoiceUrl());
        variables.put("invoicePdf", event.invoicePdf());

        send(envelope, event.firebaseUid(), TEMPLATE,
                "receipt:" + event.invoiceId(), variables);
    }
}
