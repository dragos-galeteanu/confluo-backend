-- Who the people on each side of a payment are, as Stripe knows them.
--
-- Versions 1300-1399 belong to the onboarding module. Nothing here points outwards either: these
-- three tables map this platform's identities onto Stripe's, which is the one fact about a payment
-- that Stripe cannot be asked for.
--
-- Two modules' foreign keys point IN to connected_accounts - marketplace's payments and payouts'
-- movements - which is why this migration runs before both of them.
--
-- The conventions in V1 hold here too: BIGINT minor units, uppercase ISO 4217 with a CHECK that
-- says so, and the five BaseJpaEntity columns on every table.

-- The mapping from this platform's identity to Stripe's, and nothing else.
--
-- Two identifiers is the entire table on purpose. A customer's address, their default payment
-- method, their tax status - Stripe holds all of it, keeps it current, and is asked directly on the
-- rare path that needs it. What cannot be asked for is which Stripe customer belongs to which
-- Firebase uid, because that is this platform's own fact, so that is what is kept.
CREATE TABLE stripe_customers (
    id                      UUID            PRIMARY KEY,
    created_at              TIMESTAMPTZ     NOT NULL,
    updated_at              TIMESTAMPTZ     NOT NULL,
    disabled_at             TIMESTAMPTZ,
    deleted_at              TIMESTAMPTZ,
    firebase_uid            VARCHAR(128)    NOT NULL,
    stripe_customer_id      VARCHAR(255)    NOT NULL,
    CONSTRAINT uq_stripe_customers_firebase_uid UNIQUE (firebase_uid),
    -- Both directions are unique. Two uids pointing at one Stripe customer would bill one person
    -- for another's subscription, which is the worst failure this table can have.
    CONSTRAINT uq_stripe_customers_stripe_id UNIQUE (stripe_customer_id)
);

-- The people who get paid, and what Stripe currently permits them.
--
-- Every column below the identifiers is Stripe's answer, written by the `account.updated` webhook
-- and by nothing else. This service never decides that somebody is verified; it records that Stripe
-- has, which is the only form of that statement anyone can act on.
--
-- There is no state column, and that is deliberate: com.confluo.payments.connect.ConnectedAccountState
-- is derived from the four fields below whenever it is asked for. Storing it would be a fifth field
-- that has to agree with the other four, and it is the one that would go stale.
CREATE TABLE connected_accounts (
    id                          UUID            PRIMARY KEY,
    created_at                  TIMESTAMPTZ     NOT NULL,
    updated_at                  TIMESTAMPTZ     NOT NULL,
    disabled_at                 TIMESTAMPTZ,
    deleted_at                  TIMESTAMPTZ,
    firebase_uid                VARCHAR(128)    NOT NULL,
    stripe_account_id           VARCHAR(255)    NOT NULL,
    -- Set when the account is created and never again. It decides what Stripe requires of the
    -- person and what currencies they can be paid in, and it cannot be changed afterwards - a
    -- person who moves country needs a new account, which is Stripe's rule and not this one.
    -- VARCHAR and not CHAR(2): PostgreSQL's char(n) blank-pads, which makes comparisons
    -- surprising, and Hibernate maps a String to varchar - so a CHAR column fails ddl-auto
    -- validate on a schema that is otherwise correct. The CHECK below is what makes it two
    -- uppercase letters.
    country                     VARCHAR(2)      NOT NULL,
    default_currency            VARCHAR(3)      NOT NULL,
    details_submitted           BOOLEAN         NOT NULL DEFAULT FALSE,
    charges_enabled             BOOLEAN         NOT NULL DEFAULT FALSE,
    payouts_enabled             BOOLEAN         NOT NULL DEFAULT FALSE,
    -- Stripe's reason for withholding charges or payouts, free text because they add reasons.
    -- Everything meaning "rejected" begins `rejected.`, which is what ConnectedAccountState keys
    -- off rather than an exhaustive list that would need a release to extend.
    disabled_reason             VARCHAR(64),
    -- What Stripe still wants, as it says it: a JSON array of requirement names such as
    -- ["individual.verification.document"]. Kept as a document because the list is Stripe's to
    -- shape and the only thing done with it is showing the person what is outstanding.
    requirements_currently_due  JSONB           NOT NULL DEFAULT '[]',
    requirements_past_due       JSONB           NOT NULL DEFAULT '[]',
    onboarding_completed_at     TIMESTAMPTZ,
    -- The timestamp of the most recent account event applied to this row. Webhooks have no ordering
    -- guarantee, so a handler compares against this and ignores anything older - otherwise a
    -- delayed event can resurrect a state Stripe has already moved on from.
    --
    -- NULLABLE, and that is load-bearing: null means no event has been applied yet, which is the
    -- state of a row the moment it is created. Defaulting it to now() instead would make the FIRST
    -- account.updated Stripe sends look older than the row whenever Stripe's clock is a moment
    -- behind - and that event is the one that enables the seller, so losing it leaves them
    -- permanently unable to be paid.
    last_account_event_at       TIMESTAMPTZ,
    CONSTRAINT uq_connected_accounts_firebase_uid UNIQUE (firebase_uid),
    CONSTRAINT uq_connected_accounts_stripe_id UNIQUE (stripe_account_id),
    CONSTRAINT ck_connected_accounts_country CHECK (country ~ '^[A-Z]{2}$'),
    CONSTRAINT ck_connected_accounts_currency
        CHECK (default_currency = upper(default_currency) AND default_currency ~ '^[A-Z]{3}$')
);

-- Stripe Identity sessions: whether a person is who they say they are.
--
-- Separate from connected_accounts, because the two answer different questions and conflating them
-- produces a user who has passed identity verification and still cannot be paid, with no way to
-- tell which half is the problem. One person can have several sessions over time - a failed check
-- is retried - so the uid is indexed rather than unique.
CREATE TABLE identity_verifications (
    id                              UUID            PRIMARY KEY,
    created_at                      TIMESTAMPTZ     NOT NULL,
    updated_at                      TIMESTAMPTZ     NOT NULL,
    disabled_at                     TIMESTAMPTZ,
    deleted_at                      TIMESTAMPTZ,
    firebase_uid                    VARCHAR(128)    NOT NULL,
    stripe_verification_session_id  VARCHAR(255)    NOT NULL,
    status                          VARCHAR(24)     NOT NULL,
    verified_at                     TIMESTAMPTZ,
    -- Why the last attempt did not satisfy Stripe, which is what the person has to be told. The
    -- code is Stripe's and kept as free text; the reason is theirs to word.
    last_error_code                 VARCHAR(64),
    last_error_reason               TEXT,
    CONSTRAINT uq_identity_verifications_session UNIQUE (stripe_verification_session_id),
    CONSTRAINT ck_identity_verifications_status
        CHECK (status IN ('REQUIRES_INPUT', 'PROCESSING', 'VERIFIED', 'CANCELED'))
);

CREATE INDEX ix_identity_verifications_firebase_uid ON identity_verifications (firebase_uid);

-- The listings filter on these, and every resource row carries one.
CREATE INDEX ix_stripe_customers_deleted_at ON stripe_customers (deleted_at);
CREATE INDEX ix_connected_accounts_deleted_at ON connected_accounts (deleted_at);
CREATE INDEX ix_identity_verifications_deleted_at ON identity_verifications (deleted_at);
