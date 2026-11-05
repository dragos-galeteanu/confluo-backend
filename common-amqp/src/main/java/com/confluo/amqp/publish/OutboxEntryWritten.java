package com.confluo.amqp.publish;

import java.util.UUID;

/**
 * Says that an event has been written to the outbox and the transaction is on its way to commit.
 *
 * <p>An application event rather than a direct call, so the publisher does not have to know the
 * drainer exists and a service can listen for the same thing if it wants to. It is delivered after
 * the commit, which is the earliest moment publishing is safe.</p>
 *
 * @param entryId the outbox entry's id, which is the event's id
 */
public record OutboxEntryWritten(UUID entryId) {
}
