package com.confluo.notifications.listener;

import com.confluo.amqp.consume.EventHandler;
import com.confluo.amqp.event.EventEnvelope;
import com.confluo.amqp.event.payments.InvoicePaymentFailedEvent;
import com.confluo.notifications.service.NotificationService;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Asks somebody to fix their payment method when a renewal fails.
 *
 * <p>The dunning sequence, and the one message here that is sent <strong>more than once</strong> for
 * one problem. The payments service publishes this on every collection attempt rather than only the
 * first, because Stripe retries on its own schedule and each failure is a new fact - so the
 * deduplication key includes the attempt number and each attempt gets its own reminder.</p>
 *
 * <p>{@code nextPaymentAttempt} is Stripe's, which is what makes "we will try again on Tuesday"
 * true rather than a guess. A <strong>null</strong> one means Stripe has given up, and that is the
 * last reminder before the subscription lapses - worth wording differently, which is why the
 * template is given the value rather than a formatted sentence.</p>
 *
 * <h2>Why nothing listens to subscription.past_due</h2>
 *
 * <p>The payments service also publishes {@code subscription.past_due} for the same lapse, and
 * deliberately nothing here consumes it. Both events describe one failed renewal, so handling both
 * would send two emails about it. The invoice event is the one that carries the amount owed, the
 * attempt number and the retry date - everything a reminder needs - while the subscription event
 * exists for consumers that grant or withhold access. Sending from the wrong one of the two would
 * produce a message that could not say what was owed.</p>
 */
@Component
public class OnInvoicePaymentFailed extends PaymentEventNotifier
        implements EventHandler<InvoicePaymentFailedEvent> {

    /** The template this sends. TRANSACTIONAL: it is about money owed, not marketing. */
    static final String TEMPLATE = "invoice.payment_failed";

    /**
     * @param notifications the send pipeline
     */
    public OnInvoicePaymentFailed(NotificationService notifications) {
        super(notifications);
    }

    @Override
    public String producer() {
        return PRODUCER;
    }

    @Override
    public void handle(EventEnvelope<InvoicePaymentFailedEvent> envelope) {
        InvoicePaymentFailedEvent event = envelope.payload();

        Map<String, Object> variables = new HashMap<>();
        variables.put("invoiceNumber", event.number());
        variables.put("amountDue", event.amountDue());
        variables.put("currency", event.currency());
        variables.put("attemptCount", event.attemptCount());
        variables.put("nextPaymentAttempt", event.nextPaymentAttempt());
        // Null when Stripe has stopped trying: the template can say so instead of promising
        // another attempt that will never come.
        variables.put("finalAttempt", event.nextPaymentAttempt() == null);
        // Where they can pay it themselves rather than waiting for the next retry.
        variables.put("hostedInvoiceUrl", event.hostedInvoiceUrl());

        send(envelope, event.firebaseUid(), TEMPLATE,
                // Per attempt, not per invoice: each failure gets its own reminder.
                "dunning:%s:%d".formatted(event.invoiceId(), event.attemptCount()),
                variables);
    }
}
