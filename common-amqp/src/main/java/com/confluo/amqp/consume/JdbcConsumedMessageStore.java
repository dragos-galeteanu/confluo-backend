package com.confluo.amqp.consume;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * The consumed-message ledger on PostgreSQL, through JDBC for the same reasons as the outbox.
 *
 * <p>The claim is an insert with {@code ON CONFLICT DO NOTHING} rather than a read followed by a
 * write, which matters for two separate reasons. A read-then-write has a window in which two
 * deliveries of one event both see nothing and both proceed. And letting the insert fail on the
 * primary key instead would abort the whole transaction in PostgreSQL - taking the handler's own
 * work with it - so a duplicate would look like a failure and be retried forever.</p>
 */
public class JdbcConsumedMessageStore implements ConsumedMessageStore {

    private static final String CLAIM = """
            INSERT INTO consumed_message (event_id, handler, consumed_at)
            VALUES (:eventId, :handler, now())
            ON CONFLICT (event_id, handler) DO NOTHING
            """;

    private static final String PURGE = "DELETE FROM consumed_message WHERE consumed_at < :cutoff";

    private final NamedParameterJdbcTemplate jdbc;

    /**
     * @param jdbc a template on the same DataSource the handlers write to
     */
    public JdbcConsumedMessageStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean markConsumed(UUID eventId, String handler) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "Claiming an event needs an active transaction, shared with the handler; "
                            + "without one a handler that fails would still look done");
        }
        int inserted = jdbc.update(CLAIM, new MapSqlParameterSource()
                .addValue("eventId", eventId)
                .addValue("handler", handler));
        return inserted == 1;
    }

    @Override
    public int purgeConsumedBefore(Instant cutoff) {
        return jdbc.update(PURGE, new MapSqlParameterSource()
                .addValue("cutoff", OffsetDateTime.ofInstant(cutoff, ZoneOffset.UTC)));
    }
}
