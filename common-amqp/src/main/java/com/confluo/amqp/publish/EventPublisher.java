package com.confluo.amqp.publish;

import java.util.UUID;

/**
 * How a service publishes an event. The only messaging API a service author needs on this side.
 *
 * <p>Must be called inside the transaction that makes the change the event is about. That is not a
 * convenience: the event is written to the outbox in that same transaction, so the two either both
 * happen or neither does. Calling this outside a transaction is refused rather than allowed to
 * quietly announce something that may not have been saved.</p>
 *
 * <p>Nothing reaches the broker before the commit, and the call does not wait for it afterwards.
 * What a caller gets back is the event's id, which is worth logging: it is the same id the outbox
 * row, the message and every consumer's ledger entry carry.</p>
 *
 * <p>Payloads must be annotated with {@code @DomainEvent} and live in the contracts package. An
 * unregistered record is rejected immediately, in the caller's own stack, rather than becoming a
 * row nothing can ever publish.</p>
 */
public interface EventPublisher {

    /**
     * Publishes an event about a particular thing.
     *
     * @param aggregateId the id of what the event happened to, which consumers key off
     * @param payload the event record
     * @param <T> the event record type
     * @return the event's id
     */
    <T> UUID publish(UUID aggregateId, T payload);

    /**
     * Publishes an event that is not about one identifiable thing.
     *
     * @param payload the event record
     * @param <T> the event record type
     * @return the event's id
     */
    default <T> UUID publish(T payload) {
        return publish(null, payload);
    }
}
