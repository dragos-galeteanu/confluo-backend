-- The transactional outbox and the idempotency ledger.
--
-- Versioned 1000 rather than 1 on purpose. A service configures both its own migration location
-- and this one, and Flyway requires versions to be unique across every location it is given - two
-- migrations numbered 1 would stop the service with "Found more than one migration with version 1".
-- Versions 1-999 belong to a service's own schema; 1000-1099 are reserved for common-amqp. A
-- shared module numbering from 1 could not be added to any service that already had migrations.
--
-- Neither table is a domain resource, so neither carries the five columns of
-- com.confluo.common.jpa.entity.BaseJpaEntity: there is nothing here to disable or soft-delete, and
-- a published row is a fact rather than a resource. Both are reached through JdbcTemplate rather
-- than Hibernate, so ddl-auto: validate has no opinion about them.

CREATE TABLE outbox (
    id              UUID            PRIMARY KEY,
    exchange        VARCHAR(255)    NOT NULL,
    routing_key     VARCHAR(255)    NOT NULL,
    event_type      VARCHAR(255)    NOT NULL,
    -- The version belongs next to the type because the two together identify a contract: during a
    -- migration two records share a type and differ only by version. Stored rather than derived so
    -- the drainer can stamp the message headers without parsing a payload it treats as opaque.
    event_version   INTEGER         NOT NULL,
    -- TEXT rather than JSONB, which would be the obvious choice and is the wrong one. A row here
    -- holds a message in transit, to be republished exactly as it was written; jsonb parses and
    -- re-renders what it stores, reordering keys and changing whitespace, so what came back out
    -- would not be what went in. The trade-off is no insert-time JSON validation, which guards
    -- little when the only writer is this module's own converter. The one field worth querying has
    -- its own column above.
    payload         TEXT            NOT NULL,
    created_at      TIMESTAMPTZ     NOT NULL,
    published_at    TIMESTAMPTZ,
    attempts        INTEGER         NOT NULL DEFAULT 0,
    last_error      TEXT,
    next_attempt_at TIMESTAMPTZ     NOT NULL,
    CONSTRAINT ck_outbox_attempts CHECK (attempts >= 0),
    CONSTRAINT ck_outbox_event_version CHECK (event_version >= 1)
);

-- The id is the event's own id, so a row, a log line and a consumer's ledger entry all line up.

-- Partial, because the drainer only ever reads unpublished rows and published ones are the vast
-- majority. Keeping them out of the index is what stops it growing with the table.
CREATE INDEX ix_outbox_pending ON outbox (next_attempt_at, created_at) WHERE published_at IS NULL;

CREATE TABLE consumed_message (
    event_id    UUID            NOT NULL,
    handler     VARCHAR(255)    NOT NULL,
    consumed_at TIMESTAMPTZ     NOT NULL,
    CONSTRAINT pk_consumed_message PRIMARY KEY (event_id, handler)
);

-- Keyed by handler as well as event, because two handlers in one service may both consume an event
-- and each has to process it once.

-- For the purge, which is the only query that does not go through the primary key.
CREATE INDEX ix_consumed_message_consumed_at ON consumed_message (consumed_at);
