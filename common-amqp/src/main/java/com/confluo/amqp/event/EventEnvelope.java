package com.confluo.amqp.event;

import java.time.Instant;
import java.util.UUID;

/**
 * What actually travels: the event itself, wrapped in everything a consumer needs that is not part
 * of the event's own meaning.
 *
 * <p>The fields outside {@code payload} are there for specific jobs rather than for completeness.
 * {@code eventId} is what makes a duplicate delivery recognisable, and so is what the idempotency
 * ledger is keyed by. {@code eventType} and {@code version} are what a consumer resolves the
 * payload class from, which is why a message can be decoded without knowing in advance which
 * record it holds. {@code occurredAt} is how a handler tells a stale event from a current one,
 * which matters because concurrency above one means messages are not delivered in order.
 * {@code traceId} repeats what the message headers already carry, for the case where they are
 * lost. {@code actorId} is the Firebase uid of whoever caused the change, so a consumer can attribute
 * it without calling back.</p>
 *
 * <p>Validation is in the canonical constructor, so it applies to a hand-built envelope and to a
 * decoded one alike - Jackson instantiates a record through the same constructor.</p>
 *
 * @param eventId this event's identity, unique across every publish
 * @param eventType the wire name, {@code <aggregate>.<event>}
 * @param version the contract version of {@code payload}
 * @param source the service that published it
 * @param aggregateId the id of the thing it happened to, null for an event with no aggregate
 * @param occurredAt when it happened, not when it was published
 * @param traceId the trace this event belongs to, null when nothing is tracing
 * @param correlationId what ties this event to a wider conversation, null when there is none
 * @param actorId the Firebase uid behind the change, null when nothing caused it but the system
 * @param payload the event record itself
 * @param <T> the event record type, annotated with {@link DomainEvent}
 */
public record EventEnvelope<T>(
        UUID eventId,
        String eventType,
        int version,
        String source,
        UUID aggregateId,
        Instant occurredAt,
        String traceId,
        String correlationId,
        String actorId,
        T payload) {

    /**
     * Rejects an envelope that could not be acted on.
     *
     * <p>The four required fields are the ones whose absence has no sensible fallback: without an
     * id a duplicate cannot be spotted, without a type the payload cannot be decoded, without a
     * source a consumer cannot say where a message came from, and without a timestamp a handler
     * cannot order anything. The rest are genuinely optional and stay nullable.</p>
     */
    public EventEnvelope {
        if (eventId == null) {
            throw new IllegalArgumentException("An event needs an eventId");
        }
        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("Event %s needs an eventType".formatted(eventId));
        }
        if (version < 1) {
            throw new IllegalArgumentException(
                    "Event %s has version %d; versions start at 1".formatted(eventId, version));
        }
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("Event %s needs a source".formatted(eventId));
        }
        if (occurredAt == null) {
            throw new IllegalArgumentException("Event %s needs an occurredAt".formatted(eventId));
        }
        if (payload == null) {
            throw new IllegalArgumentException("Event %s needs a payload".formatted(eventId));
        }
    }

    /**
     * Wraps a payload for publishing, stamping the parts only the publisher knows.
     *
     * <p>The id is generated here rather than by the caller, and {@code occurredAt} is taken here
     * rather than at the broker, because the event happened when the transaction that produced it
     * ran - not when the outbox drainer got round to it, which can be a poll interval or an outage
     * later.</p>
     *
     * @param descriptor the payload's contract, which supplies the type and version
     * @param aggregateId the id of the thing it happened to, may be null
     * @param payload the event record
     * @param source the publishing service's name
     * @param traceId the current trace, may be null
     * @param correlationId the wider conversation, may be null
     * @param actorId the Firebase uid behind the change, may be null
     * @param <T> the event record type
     * @return a complete envelope
     */
    public static <T> EventEnvelope<T> create(
            EventDescriptor descriptor,
            UUID aggregateId,
            T payload,
            String source,
            String traceId,
            String correlationId,
            String actorId) {
        return new EventEnvelope<>(
                UUID.randomUUID(),
                descriptor.type(),
                descriptor.version(),
                source,
                aggregateId,
                Instant.now(),
                traceId,
                correlationId,
                actorId,
                payload);
    }
}
