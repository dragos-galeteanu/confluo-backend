package com.confluo.amqp.outbox;

import java.time.Instant;
import java.util.UUID;

/**
 * One event waiting to be published, as a row of the outbox.
 *
 * <p>Part of the transactional outbox pattern. Publishing inside a database transaction can succeed
 * while the transaction rolls back, which leaves consumers acting on something that never happened;
 * publishing after the commit can be lost if the process dies in between, which leaves them never
 * hearing about something that did. Writing the event to this table in the same transaction as the
 * business change and publishing from here afterwards removes both cases. Together with the
 * consumed-message ledger on the other side, that is what makes an operation safe to retry.</p>
 *
 * <p>The id is the event's own id rather than a key of its own, so a row here, a log line and a
 * consumer's ledger entry all refer to the same thing. It also means writing the same event twice
 * is a primary key violation rather than two publishes.</p>
 *
 * <p>The payload is the whole serialized envelope, not just the event, and the drainer publishes
 * those bytes exactly as they were written. Re-serializing at publish time would make the message a
 * consumer receives depend on code deployed after the event happened. That is also why the column
 * is {@code TEXT} rather than {@code jsonb}, which would quietly re-render what it stores.</p>
 *
 * @param id the event's id, which is this row's primary key
 * @param exchange where to publish it
 * @param routingKey the key to publish it with
 * @param eventType the wire name, kept as its own column so the table can be read without parsing
 *                  the payload
 * @param eventVersion the contract version, which with the type identifies the contract
 * @param payload the serialized envelope
 * @param createdAt when the event happened, which is when the transaction that produced it ran
 * @param publishedAt when the broker confirmed it, or null while it is still pending
 * @param attempts how many times publishing has been tried and failed
 * @param lastError why the last attempt failed, or null if none has
 * @param nextAttemptAt the earliest the drainer may try again
 */
public record OutboxEntry(
        UUID id,
        String exchange,
        String routingKey,
        String eventType,
        int eventVersion,
        String payload,
        Instant createdAt,
        Instant publishedAt,
        int attempts,
        String lastError,
        Instant nextAttemptAt) {

    /**
     * Rejects a row that could never be published.
     */
    public OutboxEntry {
        if (id == null) {
            throw new IllegalArgumentException("An outbox entry needs an id");
        }
        if (exchange == null) {
            throw new IllegalArgumentException("Entry %s needs an exchange".formatted(id));
        }
        if (routingKey == null || routingKey.isBlank()) {
            throw new IllegalArgumentException("Entry %s needs a routing key".formatted(id));
        }
        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("Entry %s needs an event type".formatted(id));
        }
        if (eventVersion < 1) {
            throw new IllegalArgumentException(
                    "Entry %s has event version %d; versions start at 1".formatted(id, eventVersion));
        }
        if (payload == null || payload.isBlank()) {
            throw new IllegalArgumentException("Entry %s needs a payload".formatted(id));
        }
        if (createdAt == null) {
            throw new IllegalArgumentException("Entry %s needs a createdAt".formatted(id));
        }
        if (nextAttemptAt == null) {
            throw new IllegalArgumentException("Entry %s needs a nextAttemptAt".formatted(id));
        }
        if (attempts < 0) {
            throw new IllegalArgumentException(
                    "Entry %s has %d attempts".formatted(id, attempts));
        }
    }

    /**
     * A new entry, due immediately.
     *
     * <p>{@code nextAttemptAt} starts equal to {@code createdAt} rather than null, so the drainer's
     * query never has to special-case a row that has not been tried yet.</p>
     *
     * @param eventId the event's id
     * @param exchange where to publish it
     * @param routingKey the key to publish it with
     * @param eventType the wire name
     * @param eventVersion the contract version
     * @param payload the serialized envelope
     * @param occurredAt when the event happened
     * @return a pending entry
     */
    public static OutboxEntry pending(
            UUID eventId,
            String exchange,
            String routingKey,
            String eventType,
            int eventVersion,
            String payload,
            Instant occurredAt) {
        return new OutboxEntry(
                eventId, exchange, routingKey, eventType, eventVersion, payload, occurredAt, null, 0,
                null, occurredAt);
    }

    /**
     * Whether the broker has confirmed this event.
     * @return true once it has been published
     */
    public boolean isPublished() {
        return publishedAt != null;
    }
}
