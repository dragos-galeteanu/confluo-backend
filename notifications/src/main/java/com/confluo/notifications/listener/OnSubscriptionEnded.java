package com.confluo.notifications.listener;

import com.confluo.amqp.consume.EventHandler;
import com.confluo.amqp.event.EventEnvelope;
import com.confluo.amqp.event.payments.SubscriptionEndedEvent;
import com.confluo.notifications.service.NotificationService;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Confirms that a subscription has ended.
 *
 * <p>Sent when it is actually over, which is not when somebody asked for it to be: a subscription
 * cancelled at the period end stays live and paid for until that period closes, and the payments
 * service publishes nothing for the request. So this message never arrives while somebody still has
 * what they paid for.</p>
 *
 * <p>{@code unpaid} distinguishes the two ways a subscription ends, and the wording should differ:
 * somebody who cancelled knows why, while somebody whose payments ran out may not realise they have
 * lost access.</p>
 */
@Component
public class OnSubscriptionEnded extends PaymentEventNotifier
        implements EventHandler<SubscriptionEndedEvent> {

    /** The template this sends. ACCOUNT: it is about their account rather than a charge. */
    static final String TEMPLATE = "subscription.ended";

    /**
     * @param notifications the send pipeline
     */
    public OnSubscriptionEnded(NotificationService notifications) {
        super(notifications);
    }

    @Override
    public String producer() {
        return PRODUCER;
    }

    @Override
    public void handle(EventEnvelope<SubscriptionEndedEvent> envelope) {
        SubscriptionEndedEvent event = envelope.payload();

        Map<String, Object> variables = new HashMap<>();
        variables.put("planKey", event.planKey());
        variables.put("audience", event.audience());
        variables.put("unpaid", event.unpaid());

        send(envelope, event.firebaseUid(), TEMPLATE,
                "subscription-ended:" + event.subscriptionId(), variables);
    }
}
