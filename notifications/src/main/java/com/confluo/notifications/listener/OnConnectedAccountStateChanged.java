package com.confluo.notifications.listener;

import com.confluo.amqp.consume.EventHandler;
import com.confluo.amqp.event.EventEnvelope;
import com.confluo.amqp.event.payments.ConnectedAccountStateChangedEvent;
import com.confluo.notifications.service.NotificationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Tells a seller when Stripe changes what they are allowed to do.
 *
 * <p>Two messages from one event, chosen by where the account ended up. A seller who has just
 * become payable wants to know they can start selling; one who has been restricted wants to know
 * <strong>what Stripe is waiting for</strong>, which is the whole value of the event - "your
 * account is restricted" sends them to support, and "Stripe still needs your identity document"
 * lets them act.</p>
 *
 * <p>A move into {@code NOT_STARTED} sends nothing: somebody who has not begun onboarding has
 * nothing to be reminded of yet, and a message about an account they have not created would be
 * their first contact with it.</p>
 *
 * <h2>No deduplication key</h2>
 *
 * <p>Deliberately null, which is the only listener here that does that. A seller can genuinely be
 * restricted, fix it, and be restricted again - and each of those deserves telling. The
 * consumed-message ledger already guarantees one message per event; a key keyed on the state would
 * suppress the second real transition, which is the one somebody most needs to hear about.</p>
 */
@Component
@Slf4j
public class OnConnectedAccountStateChanged extends PaymentEventNotifier
        implements EventHandler<ConnectedAccountStateChangedEvent> {

    /** Stripe is satisfied and money can reach their bank. ACCOUNT category. */
    static final String ENABLED_TEMPLATE = "account.payouts_enabled";

    /** Stripe wants something before they can be paid. ACCOUNT category. */
    static final String REQUIREMENTS_TEMPLATE = "account.requirements_due";

    /**
     * @param notifications the send pipeline
     */
    public OnConnectedAccountStateChanged(NotificationService notifications) {
        super(notifications);
    }

    @Override
    public String producer() {
        return PRODUCER;
    }

    @Override
    public void handle(EventEnvelope<ConnectedAccountStateChangedEvent> envelope) {
        ConnectedAccountStateChangedEvent event = envelope.payload();

        String template = templateFor(event.state());
        if (template == null) {
            log.debug("Nothing to tell {} about moving to {}", event.firebaseUid(), event.state());
            return;
        }

        Map<String, Object> variables = new HashMap<>();
        variables.put("previousState", event.previousState());
        variables.put("state", event.state());
        variables.put("payoutsEnabled", event.payoutsEnabled());
        variables.put("disabledReason", event.disabledReason());
        // Stripe's own names for what is outstanding. The template turns them into something a
        // person can read; this service deliberately does not, because the list is Stripe's to
        // extend and a mapping here would go stale silently.
        variables.put("requirementsCurrentlyDue", event.requirementsCurrentlyDue());

        send(envelope, event.firebaseUid(), template, null, variables);
    }

    /**
     * Which message a state deserves, or null for one that deserves none.
     *
     * @param state where the account ended up
     * @return the template key, or null
     */
    private String templateFor(String state) {
        return switch (state) {
            case "ENABLED" -> ENABLED_TEMPLATE;
            // Rejected is terminal and nothing the seller does will change it, but they still have
            // to be told - finding out by trying to sell is worse.
            case "RESTRICTED", "REJECTED" -> REQUIREMENTS_TEMPLATE;
            case "NOT_STARTED" -> null;
            default -> {
                // A state this service does not know means the payments service has added one.
                // Saying nothing is the safe failure: a seller is not told something, rather than
                // told the wrong thing.
                log.warn("Connected account moved to state '{}', which this service does not "
                        + "recognise; nothing sent", state);
                yield null;
            }
        };
    }
}
