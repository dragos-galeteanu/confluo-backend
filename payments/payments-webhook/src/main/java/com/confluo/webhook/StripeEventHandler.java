package com.confluo.webhook;

import com.stripe.model.Event;

/**
 * Something this service does about one kind of Stripe event. The only API a handler author needs.
 *
 * <pre>{@code
 * @Component
 * @RequiredArgsConstructor
 * public class OnPaymentIntentSucceeded implements StripeEventHandler {
 *
 *     private final PaymentRepository payments;
 *
 *     @Override
 *     public String eventType() {
 *         return "payment_intent.succeeded";
 *     }
 *
 *     @Override
 *     public void handle(StripeEvent ledgerRow, Event event) {
 *         PaymentIntent intent = (PaymentIntent) event.getDataObjectDeserializer()
 *                 .getObject().orElseThrow();
 *         ...
 *     }
 * }
 * }</pre>
 *
 * <p>Declaring that is all of it. Being found, claimed, retried with a backoff and marked applied
 * are the sweep's business.</p>
 *
 * <p><strong>Runs in a transaction, and inside the same one that marks the event applied.</strong>
 * So a handler either does its work and the event becomes {@code PROCESSED}, or it throws and
 * neither happened - there is no state in which the ledger says an event was applied and the change
 * it describes is missing. Throwing is how a handler says the work did not happen.</p>
 *
 * <p><strong>Handlers have to tolerate being called out of order.</strong> Stripe guarantees no
 * ordering, so an older event can arrive after a newer one. The usual move is to ignore an event
 * whose {@link StripeEvent#getEventCreatedAt()} precedes the {@code lastStripeEventAt} of the row
 * it is about - which is what that field is on every mirrored entity for.</p>
 *
 * <p>They do <strong>not</strong> have to tolerate being called twice for the same event: the
 * ledger's unique constraint sees to that at the endpoint, and the claim sees to it between
 * instances.</p>
 */
public interface StripeEventHandler {

    /**
     * The Stripe event type this handles, e.g. {@code payment_intent.succeeded}.
     *
     * <p>Exact, not a pattern. One handler per type, and two handlers claiming the same type is a
     * startup failure rather than a coin toss - see {@link StripeEventSweeper}.</p>
     *
     * @return the type, as Stripe spells it
     */
    String eventType();

    /**
     * Does whatever this service does about the event.
     *
     * @param ledgerRow the ledger's own record of it: the event id, when Stripe says it occurred,
     *                  and which object it was about
     * @param event the event as Stripe sent it, reconstructed from the archived body
     */
    void handle(StripeEvent ledgerRow, Event event);
}
