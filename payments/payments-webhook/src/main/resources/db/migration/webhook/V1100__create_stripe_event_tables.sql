-- The webhook ledger: every event Stripe has sent, and whether it has been applied.
--
-- Versioned 1100 rather than 1, for the reason common-amqp's own migration gives: a service
-- configures its own migration location and every module's, and Flyway requires versions to be
-- unique across all of them - two migrations numbered 1 stop the service with "Found more than one
-- migration with version 1". Versions 1-999 belong to a service's own schema, 1000-1099 to
-- common-amqp, and 1100-1199 are reserved for payments-webhook.
--
-- Unlike the outbox, this table IS a resource read through Hibernate: it carries the five columns
-- of com.confluo.common.jpa.entity.BaseJpaEntity, and ddl-auto: validate has an opinion about
-- every column below. A service that adds this module's migration location but not its @EntityScan
-- gets a table nothing is mapped to; one that does the reverse fails validate at startup.

-- Every webhook Stripe has sent, and whether it has been applied.
--
-- THIS TABLE IS THE IDEMPOTENCY GUARANTEE. A row is inserted the moment an event's signature
-- verifies and before anything acts on it, so the unique constraint on the event id - and not a
-- handler's own care - is what makes applying one twice impossible. Stripe retries a webhook for
-- days and will redeliver an event it has already delivered successfully; without this, a
-- redelivered `charge.succeeded` is a second delivery of whatever was bought.
--
-- created_at IS the moment the event was received, because the row is written immediately on
-- arrival - which is why there is no received_at beside it: one fact in two columns is how the two
-- come to disagree. `processed_at` is when it was applied, which is a separate transaction and can
-- be much later if the first attempt failed.
--
-- The event's raw body is not here. It is a document in MongoDB keyed by the Stripe event id,
-- because its shape is Stripe's to change and the only things queried are the columns below.
CREATE TABLE stripe_events (
    id                      UUID            PRIMARY KEY,
    created_at              TIMESTAMPTZ     NOT NULL,
    updated_at              TIMESTAMPTZ     NOT NULL,
    disabled_at             TIMESTAMPTZ,
    deleted_at              TIMESTAMPTZ,
    stripe_event_id         VARCHAR(255)    NOT NULL,
    event_type              VARCHAR(128)    NOT NULL,
    -- The API version the event was rendered at, which is not necessarily the one this service
    -- pins: an endpoint can be registered at an older version, and an event whose version is not
    -- the expected one is worth noticing before its contents are trusted.
    api_version             VARCHAR(32),
    -- Whether this came from live mode. Stored because a test event arriving at a production
    -- endpoint, or the reverse, is otherwise invisible and explains an enormous amount.
    livemode                BOOLEAN         NOT NULL,
    -- The Stripe account the event concerns, as Stripe's own acct_ id and NOT a foreign key into
    -- connected_accounts: an event can arrive for an account this service has no row for, and
    -- refusing it would lose exactly the event that explains why.
    stripe_account_id       VARCHAR(255),
    -- The id of the object the event is about, so the row can be found from the payment rather
    -- than only the other way round.
    object_id               VARCHAR(255),
    -- When Stripe says the event OCCURRED, which is its `created` and not the object's. This is
    -- what a handler compares against the `last_stripe_event_at` of the row it is about to write,
    -- because webhooks have no ordering guarantee. The object's own creation time would be
    -- identical for every event about it and so orders nothing - which is the mistake this column
    -- name made before.
    event_created_at        TIMESTAMPTZ,
    processed_at            TIMESTAMPTZ,
    status                  VARCHAR(16)     NOT NULL,
    attempts                INTEGER         NOT NULL DEFAULT 0,
    last_error              TEXT,
    -- When the sweep may next try to apply this, which is what keeps a handler that throws from
    -- being retried in a tight loop. Defaults to now() so a freshly received event is immediately
    -- due; a failure moves it forward by a doubling backoff, exactly as the outbox's own entry
    -- does. Without this column the only alternative is re-deriving a backoff from updated_at,
    -- which cannot be indexed usefully.
    next_attempt_at         TIMESTAMPTZ     NOT NULL DEFAULT now(),
    -- The whole point of the table.
    CONSTRAINT uq_stripe_events_stripe_id UNIQUE (stripe_event_id),
    CONSTRAINT ck_stripe_events_status
        CHECK (status IN ('RECEIVED', 'PROCESSED', 'IGNORED', 'FAILED')),
    CONSTRAINT ck_stripe_events_attempts CHECK (attempts >= 0)
);

-- What the webhook sweep reads, constantly, and nothing else does. The same shape as
-- ix_outbox_pending: due-ness first, because that is what the claim filters on, then arrival order,
-- because that is what it sorts by. Partial for the reason the outbox's own index is - applied
-- events are the overwhelming majority, and keeping them out is what stops the index growing with
-- the table.
CREATE INDEX ix_stripe_events_unapplied ON stripe_events (next_attempt_at, created_at)
    WHERE status IN ('RECEIVED', 'FAILED');

-- Finding the ledger row from the object it was about, which is how "why does this payment say
-- that?" is answered.
CREATE INDEX ix_stripe_events_object ON stripe_events (object_id) WHERE object_id IS NOT NULL;

-- The listings filter on this, as they do on every resource row.
CREATE INDEX ix_stripe_events_deleted_at ON stripe_events (deleted_at);
