package com.confluo.amqp.consume;

import java.time.Instant;
import java.util.UUID;

/**
 * The record of what each handler has already dealt with.
 *
 * <p>This is the other half of the transactional outbox. Publishing is at-least-once - an entry
 * whose publish was not confirmed is published again - so a consumer will sometimes see the same
 * event twice and has to be able to tell. Keyed by handler as well as event, because two handlers
 * in one service may both consume an event and each has to process it once.</p>
 */
public interface ConsumedMessageStore {

    /**
     * Claims an event for a handler, in the caller's transaction.
     *
     * <p>Must share the handler's transaction. That is what makes the guarantee real: a handler
     * that throws rolls back its own claim, so the retry is a genuine retry rather than a message
     * marked done by a handler that never finished.</p>
     *
     * @param eventId the event's id
     * @param handler the handler's name
     * @return true if this is the first time, false if it has already been consumed
     */
    boolean markConsumed(UUID eventId, String handler);

    /**
     * Forgets what was consumed before a point in time.
     *
     * <p>The table only grows otherwise. The window has to outlive the longest possible
     * redelivery, which is why it is days rather than minutes.</p>
     *
     * @param cutoff the oldest record to keep
     * @return how many were forgotten
     */
    int purgeConsumedBefore(Instant cutoff);
}
