package com.confluo.amqp.consume;

import com.confluo.amqp.event.EventEnvelope;

/**
 * Something a service does when another service's event arrives. The only messaging API a service
 * author needs on this side.
 *
 * <pre>{@code
 * @Component
 * @RequiredArgsConstructor
 * public class OnCurrencyDisabled implements EventHandler<CurrencyDisabledEvent> {
 *
 *     private final OfferingService offerings;
 *
 *     @Override
 *     public String producer() {
 *         return "platform";
 *     }
 *
 *     @Override
 *     public void handle(EventEnvelope<CurrencyDisabledEvent> envelope) {
 *         offerings.stopOffering(envelope.payload().currencyId());
 *     }
 * }
 * }</pre>
 *
 * <p>Declaring that is all of it. The queue, its bindings, the retry tiers, the parking lot, the
 * listener container, the acknowledgements and the duplicate check are all derived from the type
 * parameter and the producer's name.</p>
 *
 * <p><strong>Handlers have to tolerate being called out of order.</strong> Concurrency is above one
 * by default and RabbitMQ gives no ordering once it is, so an older event can arrive after a newer
 * one. The usual move is to ignore an event whose {@code occurredAt} precedes the state already
 * held.</p>
 *
 * <p><strong>And to tolerate being called with an event already seen</strong> - though only in the
 * sense that it will not happen twice for the same {@code eventId}: the duplicate check sees to
 * that, in the same transaction as the handler, so a handler that throws rolls back its own marker
 * and a retry genuinely re-runs it.</p>
 *
 * @param <T> the event record this handles, annotated with {@code @DomainEvent}
 */
public interface EventHandler<T> {

    /**
     * The service that publishes the event.
     *
     * <p>Named rather than derived, because a routing key says what happened and not who said so -
     * and the queue has to name both.</p>
     *
     * @return the producing service's name, e.g. {@code platform}
     */
    String producer();

    /**
     * Does whatever this service does about the event.
     *
     * <p>Runs in a transaction. Throwing is how a handler says the work did not happen: the
     * transaction rolls back and the message is retried, then parked.</p>
     *
     * @param envelope the event and everything known about it
     */
    void handle(EventEnvelope<T> envelope);

    /**
     * What to bind the queue with, when one event type is not what is wanted.
     *
     * <p>A pattern such as {@code currency.*} subscribes to a family of events. Note that the queue
     * then receives every event matching it, and this handler is typed to one record - anything else
     * is acknowledged and ignored, with a warning. A handler that genuinely wants several types
     * wants several handlers.</p>
     *
     * @return the routing pattern, or null to bind the handled event's own type
     */
    default String routingPattern() {
        return null;
    }

    /**
     * The last segment of the queue's name.
     *
     * <p>Required with a wildcard {@link #routingPattern()}, because a pattern makes a queue name
     * nobody can read and two patterns could collide.</p>
     *
     * @return the suffix, or null to use the handled event's own type
     */
    default String queueSuffix() {
        return null;
    }

    /**
     * This handler's name in the consumed-message ledger.
     *
     * <p>Defaults to the class's own name. Worth pinning before renaming or moving a handler that
     * is already in production: the ledger is keyed by it, so changing it silently makes every
     * event this handler has already consumed look new again.</p>
     *
     * @return the name, or null to use the class name
     */
    default String name() {
        return null;
    }
}
