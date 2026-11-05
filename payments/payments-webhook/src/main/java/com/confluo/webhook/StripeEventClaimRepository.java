package com.confluo.webhook;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The webhook sweep's own queries, reached through JDBC rather than JPA.
 *
 * <p>JDBC because {@code FOR UPDATE SKIP LOCKED} is native SQL in any case, and because the three
 * writes here must be expressed in terms of the database's clock rather than an instance's - the
 * same reasoning as {@code JdbcOutboxRepository}, which this follows deliberately. A
 * {@code JdbcTemplate} on the same {@code DataSource} joins whatever transaction
 * {@code JpaTransactionManager} is running, so a handler's JPA writes and the mark applied here
 * commit together.</p>
 *
 * <p>Kept apart from {@code StripeEventRepository} for the reason that repository's javadoc gives:
 * a claim is not an ordinary read, and a derived method cannot express one.</p>
 */
@RequiredArgsConstructor
public class StripeEventClaimRepository {

    /** A row records why applying failed; it is not a place to keep a stack trace. */
    static final int MAX_ERROR_LENGTH = 2000;

    /**
     * The sweep's query.
     *
     * <p>{@code SKIP LOCKED} is what makes several instances safe: a row another instance holds is
     * passed over rather than waited for, so no instance blocks and no two apply the same event.
     * {@code now()} is the database's clock, so a skewed server cannot make an event due early.
     * The attempt cap is here rather than in the sweep so that a row which has run out of attempts
     * stops being claimed at all - it stays {@code FAILED} for a person to look at, which is what
     * the health indicator is reporting on.</p>
     *
     * <p>Ordered by arrival, not by when Stripe says the event occurred. Applying them in arrival
     * order is the best this can do - out-of-order delivery is a handler's problem, and ordering by
     * the event's own timestamp would only make the gaps harder to see.</p>
     */
    private static final String CLAIM = """
            SELECT id
            FROM stripe_events
            WHERE status IN ('RECEIVED', 'FAILED')
              AND attempts < :maxAttempts
              AND next_attempt_at <= now()
            ORDER BY created_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """;

    private static final String MARK_APPLIED = """
            UPDATE stripe_events
            SET status = :status, processed_at = now(), last_error = NULL
            WHERE id = :id
            """;

    private static final String RECORD_FAILURE = """
            UPDATE stripe_events
            SET status = 'FAILED',
                attempts = attempts + 1,
                last_error = :reason,
                next_attempt_at = now() + (:backoffSeconds * INTERVAL '1 second')
            WHERE id = :id
            """;

    private static final String OLDEST_UNAPPLIED = """
            SELECT min(created_at) FROM stripe_events WHERE status IN ('RECEIVED', 'FAILED')
            """;

    private static final String COUNT_UNAPPLIED = """
            SELECT count(*) FROM stripe_events WHERE status IN ('RECEIVED', 'FAILED')
            """;

    private static final String COUNT_EXHAUSTED = """
            SELECT count(*) FROM stripe_events WHERE status = 'FAILED' AND attempts >= :maxAttempts
            """;

    private final NamedParameterJdbcTemplate jdbc;

    /**
     * Takes the ids of up to {@code limit} events that are due, locking them for this transaction.
     *
     * @param limit how many at most
     * @param maxAttempts the attempt cap, beyond which an event is left alone
     * @return the claimed ids, in arrival order
     */
    public List<UUID> claimDue(int limit, int maxAttempts) {
        requireTransaction("Claiming Stripe events");
        if (limit < 1) {
            throw new IllegalArgumentException("A claim has to be for at least one event, not " + limit);
        }
        return jdbc.queryForList(CLAIM, new MapSqlParameterSource()
                .addValue("maxAttempts", maxAttempts)
                .addValue("limit", limit), UUID.class);
    }

    /**
     * Marks an event applied, or deliberately not applied.
     *
     * <p>Clears {@code last_error}, because a row that has since succeeded should not still carry
     * the reason an earlier attempt did not.</p>
     *
     * @param id the ledger row
     * @param applied {@code PROCESSED} or {@code IGNORED}
     */
    public void markApplied(UUID id, String applied) {
        requireTransaction("Marking a Stripe event applied");
        jdbc.update(MARK_APPLIED, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("status", applied));
    }

    /**
     * Records that applying an event failed, and when to try again.
     *
     * <p>Must run in a transaction of its own, <strong>not</strong> the one the handler threw in:
     * that transaction is being rolled back to undo the handler's partial writes, and this update
     * would go with it - leaving the event due immediately, with no attempt counted, forever.</p>
     *
     * @param id the ledger row
     * @param reason what went wrong, truncated to fit
     * @param backoff how long to wait before the next attempt
     */
    public void recordFailure(UUID id, String reason, Duration backoff) {
        if (backoff == null || backoff.isNegative()) {
            throw new IllegalArgumentException("A backoff cannot be " + backoff);
        }
        jdbc.update(RECORD_FAILURE, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("reason", truncate(reason))
                .addValue("backoffSeconds", backoff.toMillis() / 1000.0));
    }

    /**
     * When the oldest event still waiting to be applied arrived.
     * @return its arrival time, or empty if there are none
     */
    public Optional<Instant> oldestUnapplied() {
        OffsetDateTime oldest = jdbc.getJdbcOperations()
                .queryForObject(OLDEST_UNAPPLIED, OffsetDateTime.class);
        return Optional.ofNullable(oldest).map(OffsetDateTime::toInstant);
    }

    /**
     * How many events are still waiting.
     * @return the count
     */
    public int countUnapplied() {
        Integer count = jdbc.getJdbcOperations().queryForObject(COUNT_UNAPPLIED, Integer.class);
        return count == null ? 0 : count;
    }

    /**
     * How many events have run out of attempts and will never be tried again on their own.
     *
     * <p>The number worth waking somebody for. An event here is a payment, a subscription or a
     * payout this service has been told about and has not applied, and nothing further will happen
     * to it without a person.</p>
     *
     * @param maxAttempts the attempt cap
     * @return the count
     */
    public int countExhausted(int maxAttempts) {
        Integer count = jdbc.queryForObject(COUNT_EXHAUSTED,
                new MapSqlParameterSource().addValue("maxAttempts", maxAttempts), Integer.class);
        return count == null ? 0 : count;
    }

    /**
     * Refuses to claim outside a transaction.
     *
     * <p>A claim with no transaction releases its row locks the moment the statement finishes, so
     * two instances would apply the same event - silently, and only under load. Caught here rather
     * than discovered there.</p>
     *
     * @param what the operation, for the message
     */
    private static void requireTransaction(String what) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "%s needs an active transaction; without one the row locks are released at once"
                            .formatted(what));
        }
    }

    private static String truncate(String reason) {
        if (reason == null || reason.length() <= MAX_ERROR_LENGTH) {
            return reason;
        }
        return reason.substring(0, MAX_ERROR_LENGTH);
    }
}
