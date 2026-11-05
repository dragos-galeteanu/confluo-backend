-- Money leaving: a seller being paid, and a payer taking it back.
--
-- Versions 1500-1599 belong to the payouts module.
--
-- THREE FOREIGN KEYS LEAVE THIS MODULE, which is why this runs last but one: disputes and
-- payout_transfers both reference marketplace's payments (V1400), and payout_transfers also
-- references onboarding's connected_accounts (V1300). All three are columns rather than
-- associations in the entities, so they are three constraints to drop and no code to change.
--
-- The conventions in V1 hold here too: BIGINT minor units, uppercase ISO 4217 with a CHECK that
-- says so, and the five BaseJpaEntity columns on every table.

-- Chargebacks.
--
-- Worth understanding before reading the columns: under destination charges this platform is the
-- merchant of record, so a dispute debits the PLATFORM's balance, not the seller's.
-- `transfer_reversed_amount` is how much of the seller's share was recovered, and it can be less
-- than the dispute - if their balance has already been paid out, the platform absorbs the rest.
--
-- `status` and `reason` are Stripe's own and are not constrained to a list. They extend both, and
-- a CHECK here would reject the webhook rather than the value - losing an event about a dispute,
-- which has a deadline, to tidiness.
CREATE TABLE disputes (
    id                          UUID            PRIMARY KEY,
    created_at                  TIMESTAMPTZ     NOT NULL,
    updated_at                  TIMESTAMPTZ     NOT NULL,
    disabled_at                 TIMESTAMPTZ,
    deleted_at                  TIMESTAMPTZ,
    payment_id                  UUID            NOT NULL,
    stripe_dispute_id           VARCHAR(255)    NOT NULL,
    amount                      BIGINT          NOT NULL,
    currency                    VARCHAR(3)      NOT NULL,
    reason                      VARCHAR(64),
    status                      VARCHAR(32)     NOT NULL,
    -- The deadline for submitting evidence. Missing it loses the dispute by default, which is why
    -- this is a column and not something to be read out of Stripe's dashboard when somebody thinks
    -- to look.
    evidence_due_by             TIMESTAMPTZ,
    is_charge_refundable        BOOLEAN         NOT NULL DEFAULT FALSE,
    transfer_reversed_amount    BIGINT          NOT NULL DEFAULT 0,
    last_stripe_event_at        TIMESTAMPTZ,
    CONSTRAINT fk_disputes_payment FOREIGN KEY (payment_id) REFERENCES payments (id),
    CONSTRAINT uq_disputes_stripe_id UNIQUE (stripe_dispute_id),
    CONSTRAINT ck_disputes_amount CHECK (amount > 0),
    CONSTRAINT ck_disputes_currency
        CHECK (currency = upper(currency) AND currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_disputes_transfer_reversed_amount CHECK (transfer_reversed_amount >= 0)
);

-- Money leaving, in its three forms. See com.confluo.payments.payouts.PayoutTransferKind for why a
-- transfer and a payout are not the same event recorded twice: a transfer moves a sale's share onto
-- the seller's Stripe balance, and a payout moves that balance to their bank, days later.
--
-- This is what answers "when was I paid, and for what" - the question a seller asks most often, and
-- the one that cannot be answered from the sale alone.
CREATE TABLE payout_transfers (
    id                      UUID            PRIMARY KEY,
    created_at              TIMESTAMPTZ     NOT NULL,
    updated_at              TIMESTAMPTZ     NOT NULL,
    disabled_at             TIMESTAMPTZ,
    deleted_at              TIMESTAMPTZ,
    connected_account_id    UUID            NOT NULL,
    kind                    VARCHAR(16)     NOT NULL,
    -- Stripe's id for whichever of the three objects this is. Unique per kind rather than overall:
    -- the three are different object types in different namespaces, and nothing guarantees they
    -- will not collide.
    stripe_object_id        VARCHAR(255)    NOT NULL,
    -- Which sale this is the seller's share of, where that is known. Null on a payout, which
    -- aggregates whatever was on their balance and corresponds to no single sale.
    source_payment_id       UUID,
    amount                  BIGINT          NOT NULL,
    currency                VARCHAR(3)      NOT NULL,
    -- Stripe's own, unconstrained, and null for a transfer: a transfer has no status because it
    -- either happened or the API call failed.
    status                  VARCHAR(32),
    -- When the money is expected in the bank, which is not when the payout was created.
    arrival_date            TIMESTAMPTZ,
    failure_code            VARCHAR(64),
    failure_message         TEXT,
    CONSTRAINT fk_payout_transfers_connected_account
        FOREIGN KEY (connected_account_id) REFERENCES connected_accounts (id),
    CONSTRAINT fk_payout_transfers_source_payment
        FOREIGN KEY (source_payment_id) REFERENCES payments (id),
    CONSTRAINT uq_payout_transfers_stripe_object UNIQUE (kind, stripe_object_id),
    CONSTRAINT ck_payout_transfers_kind CHECK (kind IN ('TRANSFER', 'PAYOUT', 'REVERSAL')),
    CONSTRAINT ck_payout_transfers_amount CHECK (amount > 0),
    CONSTRAINT ck_payout_transfers_currency
        CHECK (currency = upper(currency) AND currency ~ '^[A-Z]{3}$')
);

CREATE INDEX ix_disputes_payment ON disputes (payment_id);
CREATE INDEX ix_payout_transfers_account ON payout_transfers (connected_account_id, created_at DESC);

-- The listings filter on these, and every resource row carries one.
CREATE INDEX ix_disputes_deleted_at ON disputes (deleted_at);
CREATE INDEX ix_payout_transfers_deleted_at ON payout_transfers (deleted_at);
