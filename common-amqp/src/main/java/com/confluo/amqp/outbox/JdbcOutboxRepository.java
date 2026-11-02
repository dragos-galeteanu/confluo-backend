package com.confluo.amqp.outbox;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The outbox on PostgreSQL, reached through JDBC rather than JPA.
 *
 * <p>JDBC for three reasons, all of them about this project rather than taste. A service scopes
 * {@code @EnableJpaRepositories} to its own packages, so a Spring Data repository under
 * {@code com.confluo.amqp} would never be created. A service declares no {@code @EntityScan}, so an
 * {@code @Entity} here would not be mapped and {@code ddl-auto: validate} would complain about a
 * table it had never been told about. And the drainer's query needs {@code FOR UPDATE SKIP LOCKED},
 * which is native SQL in any case.</p>
 *
 * <p>Nothing is given up by not using JPA: a {@code JdbcTemplate} on the same {@code DataSource}
 * joins the transaction {@code JpaTransactionManager} is managing, because the connection is bound
 * to the thread either way. The outbox insert and the business change commit together, which is the
 * only thing that matters here.</p>
 */
public class JdbcOutboxRepository implements OutboxRepository {

    /** A row records why publishing failed; it is not a place to keep a stack trace. */
    static final int MAX_ERROR_LENGTH = 2000;

    private static final String COLUMNS = """
            id, exchange, routing_key, event_type, event_version, payload, created_at,
            published_at, attempts, last_error, next_attempt_at
            """;

    private static final String INSERT = """
            INSERT INTO outbox (id, exchange, routing_key, event_type, event_version, payload,
                                created_at, published_at, attempts, last_error, next_attempt_at)
            VALUES (:id, :exchange, :routingKey, :eventType, :eventVersion, :payload,
                    :createdAt, :publishedAt, :attempts, :lastError, :nextAttemptAt)
            """;

    /**
     * The drainer's query. {@code SKIP LOCKED} is what makes several instances safe: a row another
     * instance is holding is passed over rather than waited for, so no instance blocks and no two
     * publish the same event. {@code now()} is the database's clock rather than any instance's, so
     * a skewed server cannot make an entry due early or late.
     */
    private static final String CLAIM = """
            SELECT %s
            FROM outbox
            WHERE published_at IS NULL
              AND attempts < :maxAttempts
              AND next_attempt_at <= now()
            ORDER BY created_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """.formatted(COLUMNS);

    private static final String FIND = "SELECT %s FROM outbox WHERE id = :id".formatted(COLUMNS);

    private static final String COUNT_PENDING =
            "SELECT count(*) FROM outbox WHERE published_at IS NULL";

    private static final String OLDEST_PENDING =
            "SELECT min(created_at) FROM outbox WHERE published_at IS NULL";

    private static final String MARK_PUBLISHED =
            "UPDATE outbox SET published_at = now() WHERE id = :id";

    private static final String RECORD_FAILURE = """
            UPDATE outbox
            SET attempts = attempts + 1,
                last_error = :reason,
                next_attempt_at = now() + (:backoffSeconds * INTERVAL '1 second')
            WHERE id = :id
            """;

    private static final RowMapper<OutboxEntry> MAPPER = (rs, rowNum) -> new OutboxEntry(
            rs.getObject("id", UUID.class),
            rs.getString("exchange"),
            rs.getString("routing_key"),
            rs.getString("event_type"),
            rs.getInt("event_version"),
            rs.getString("payload"),
            instant(rs, "created_at"),
            instant(rs, "published_at"),
            rs.getInt("attempts"),
            rs.getString("last_error"),
            instant(rs, "next_attempt_at"));

    private final NamedParameterJdbcTemplate jdbc;

    /**
     * @param jdbc a template on the same {@code DataSource} the service's business data is in
     */
    public JdbcOutboxRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(OutboxEntry entry) {
        requireTransaction("Writing an outbox entry");
        jdbc.update(INSERT, new MapSqlParameterSource()
                .addValue("id", entry.id())
                .addValue("exchange", entry.exchange())
                .addValue("routingKey", entry.routingKey())
                .addValue("eventType", entry.eventType())
                .addValue("eventVersion", entry.eventVersion())
                .addValue("payload", entry.payload())
                .addValue("createdAt", timestamp(entry.createdAt()))
                .addValue("publishedAt", timestamp(entry.publishedAt()))
                .addValue("attempts", entry.attempts())
                .addValue("lastError", truncate(entry.lastError()))
                .addValue("nextAttemptAt", timestamp(entry.nextAttemptAt())));
    }

    @Override
    public List<OutboxEntry> claimPending(int limit, int maxAttempts) {
        requireTransaction("Claiming outbox entries");
        if (limit < 1) {
            throw new IllegalArgumentException("A claim has to be for at least one entry, not " + limit);
        }
        return jdbc.query(CLAIM, new MapSqlParameterSource()
                .addValue("maxAttempts", maxAttempts)
                .addValue("limit", limit), MAPPER);
    }

    @Override
    public void markPublished(UUID id) {
        jdbc.update(MARK_PUBLISHED, new MapSqlParameterSource().addValue("id", id));
    }

    @Override
    public void recordFailure(UUID id, String reason, Duration backoff) {
        if (backoff == null || backoff.isNegative()) {
            throw new IllegalArgumentException("A backoff cannot be " + backoff);
        }
        jdbc.update(RECORD_FAILURE, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("reason", truncate(reason))
                .addValue("backoffSeconds", backoff.toMillis() / 1000.0));
    }

    @Override
    public Optional<OutboxEntry> find(UUID id) {
        return jdbc.query(FIND, new MapSqlParameterSource().addValue("id", id), MAPPER)
                .stream()
                .findFirst();
    }

    @Override
    public int countPending() {
        Integer pending = jdbc.getJdbcOperations().queryForObject(COUNT_PENDING, Integer.class);
        return pending == null ? 0 : pending;
    }

    @Override
    public Optional<Instant> oldestPending() {
        OffsetDateTime oldest = jdbc.getJdbcOperations()
                .queryForObject(OLDEST_PENDING, OffsetDateTime.class);
        return Optional.ofNullable(oldest).map(OffsetDateTime::toInstant);
    }

    /**
     * Refuses to touch the outbox outside a transaction.
     *
     * <p>Both of the operations this guards are meaningless without one, and neither fails on its
     * own: a save would commit by itself, which is the phantom event the pattern exists to prevent,
     * and a claim would have its row locks released the moment the statement finished, so two
     * drainers would publish the same event. Both are silent, so they are caught here instead.</p>
     *
     * @param what the operation, for the message
     */
    private static void requireTransaction(String what) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "%s needs an active transaction; that is the whole point of the outbox"
                            .formatted(what));
        }
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime timestamp(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static String truncate(String reason) {
        if (reason == null || reason.length() <= MAX_ERROR_LENGTH) {
            return reason;
        }
        return reason.substring(0, MAX_ERROR_LENGTH);
    }
}
