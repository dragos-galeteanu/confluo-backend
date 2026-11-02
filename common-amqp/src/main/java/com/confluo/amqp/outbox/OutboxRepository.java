package com.confluo.amqp.outbox;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Storage for the outbox.
 *
 * <p>An interface because the implementation has to live in the same database as the business data:
 * the whole point of the pattern is that {@link #save(OutboxEntry)} joins the transaction that made
 * the change, and a store in another database could not. The shipped implementation is for
 * PostgreSQL; a service on another store would provide its own.</p>
 */
public interface OutboxRepository {

    /**
     * Writes an entry, in the caller's transaction.
     *
     * <p>Must not be called outside one. An entry written in a transaction of its own is exactly
     * the phantom-event case the outbox exists to prevent.</p>
     *
     * @param entry the entry to write
     */
    void save(OutboxEntry entry);

    /**
     * Takes the next entries to publish, locking them against other instances.
     *
     * <p>Returns only entries that are unpublished, due, and have attempts left. The rows are
     * locked for the caller's transaction and skipped by anyone else's, which is what lets every
     * instance run a drainer with no leader election and no lock table. Must be called inside a
     * transaction, or the lock is released before the caller can do anything with the rows.</p>
     *
     * @param limit how many to take at most
     * @param maxAttempts the attempt cap; an entry at or above it is left alone
     * @return the claimed entries, oldest first
     */
    List<OutboxEntry> claimPending(int limit, int maxAttempts);

    /**
     * Marks an entry published, using the database's clock.
     *
     * <p>Called only once the broker has confirmed the publish. Marking on a fire-and-forget send
     * would record an event as delivered that may never have reached the broker at all.</p>
     *
     * @param id the entry's id
     */
    void markPublished(UUID id);

    /**
     * Records a failed attempt and pushes the entry into the future.
     *
     * @param id the entry's id
     * @param reason why it failed, truncated if it is long
     * @param backoff how long to wait before the next attempt
     */
    void recordFailure(UUID id, String reason, Duration backoff);

    /**
     * One entry, for the publisher that has just written it and for diagnostics.
     * @param id the entry's id
     * @return the entry, or empty if there is no such row
     */
    Optional<OutboxEntry> find(UUID id);

    /**
     * How many events are still waiting.
     *
     * <p>Read without a transaction and without locking anything, because this is for a gauge and
     * a health check rather than for publishing. A number that is a moment out of date is fine;
     * holding locks to produce it would not be.</p>
     *
     * @return the number of unpublished entries
     */
    int countPending();

    /**
     * When the oldest event still waiting was written.
     *
     * <p>The one number that says whether the outbox is moving. A large backlog that is seconds old
     * is a busy service; a single entry that is an hour old is a broken one.</p>
     *
     * @return the oldest unpublished entry's timestamp, or empty if there are none
     */
    Optional<Instant> oldestPending();
}
