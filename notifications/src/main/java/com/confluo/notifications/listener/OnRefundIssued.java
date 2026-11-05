package com.confluo.notifications.listener;

import com.confluo.amqp.consume.EventHandler;
import com.confluo.amqp.event.EventEnvelope;
import com.confluo.amqp.event.payments.RefundIssuedEvent;
import com.confluo.notifications.service.NotificationService;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Tells somebody their money is on its way back.
 *
 * <p>Sent when a refund succeeds, which is when Stripe has handed it to the payer's bank - not when
 * it was requested. The difference matters for the wording: "your refund has been sent" is true at
 * this point, and "the money is in your account" is not, because a bank transfer takes days.</p>
 *
 * <p>{@code fullyRefunded} is passed through so the template can tell a part refund from a whole
 * one without doing arithmetic on two amounts.</p>
 */
@Component
public class OnRefundIssued extends PaymentEventNotifier implements EventHandler<RefundIssuedEvent> {

    /** The template this sends. TRANSACTIONAL: it is about money that has moved. */
    static final String TEMPLATE = "refund.issued";

    /**
     * @param notifications the send pipeline
     */
    public OnRefundIssued(NotificationService notifications) {
        super(notifications);
    }

    @Override
    public String producer() {
        return PRODUCER;
    }

    @Override
    public void handle(EventEnvelope<RefundIssuedEvent> envelope) {
        RefundIssuedEvent event = envelope.payload();

        Map<String, Object> variables = new HashMap<>();
        variables.put("amount", event.amount());
        variables.put("currency", event.currency());
        variables.put("fullyRefunded", event.fullyRefunded());
        variables.put("orderReferenceType", event.orderReferenceType());
        variables.put("orderReferenceId", event.orderReferenceId());

        send(envelope, event.firebaseUid(), TEMPLATE,
                "refund:" + event.refundId(), variables);
    }
}
