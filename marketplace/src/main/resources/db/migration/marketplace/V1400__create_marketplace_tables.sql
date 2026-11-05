-- A sale between two people, and money sent back on one.
--
-- Versions 1400-1499 belong to the marketplace module.
--
-- ONE FOREIGN KEY LEAVES THIS MODULE: payments.connected_account_id references onboarding's
-- connected_accounts, which is why this runs after V1300. It is also the only thing tying the two
-- together - the entity holds a UUID rather than an association, and the seller's uid and account
-- are copied onto the payment row - so a service split drops this constraint and changes nothing
-- else.
--
-- The conventions in V1 hold here too: BIGINT minor units, uppercase ISO 4217 with a CHECK that
-- says so, and the five BaseJpaEntity columns on every table.

-- One row per payment that should exist, written before the payer is sent to Stripe.
--
-- The same shape as the notifications service's own queue, and for the same reason: the row is
-- committed first, so a webhook arriving a second later always has something to attach to. A
-- payment whose row was written afterwards is a payment that can succeed with nothing recording it.
--
-- `status` is this service's own reduction of Stripe's PaymentIntent statuses - see
-- com.confluo.payments.marketplace.PaymentStatus for why the four 'requires_*' states are one here.
CREATE TABLE payments (
    id                          UUID            PRIMARY KEY,
    created_at                  TIMESTAMPTZ     NOT NULL,
    updated_at                  TIMESTAMPTZ     NOT NULL,
    disabled_at                 TIMESTAMPTZ,
    deleted_at                  TIMESTAMPTZ,
    -- Null until Stripe creates the intent, which happens when the payer reaches the hosted page -
    -- so a session abandoned at the redirect has a row here and no intent, forever.
    stripe_payment_intent_id    VARCHAR(255),
    stripe_checkout_session_id  VARCHAR(255),
    -- Stripe's charge, which is not the same as the intent: an intent is the attempt and a charge
    -- is the one that worked. Kept because a transfer names its source as a CHARGE and nothing
    -- else, so without this a seller's payout can never be traced back to the sale it came from.
    stripe_charge_id            VARCHAR(255),
    firebase_uid                VARCHAR(128)    NOT NULL,
    -- Null for a payment to the platform itself, such as a subscription invoice. Set for a
    -- marketplace sale, where it names who the money is being transferred on to.
    --
    -- The foreign key below is the last thing tying a sale to the connected accounts table. It is
    -- deliberately the only thing: the entity holds a UUID rather than a @ManyToOne, so when
    -- onboarding becomes its own service with its own database, this constraint is dropped and
    -- nothing in the code has to change.
    connected_account_id        UUID,
    -- The seller, copied from the account at the moment the payment was recorded rather than
    -- joined for afterwards. Everything that asks about a sale asks this: the event announcing it,
    -- a dispute naming a seller, a clawback logging whose money moved.
    seller_firebase_uid         VARCHAR(128),
    -- And the acct_ the money actually went to. Nothing reads it yet. It is here because under
    -- destination charges this is a fact about the charge, and a seller who later connects a
    -- different Stripe account would otherwise make it unanswerable from this row.
    seller_stripe_account_id    VARCHAR(255),
    -- What was bought, as a reference this service does not interpret. The service that owns orders
    -- does not exist yet; when it does, this is the only coupling between the two - and it is
    -- deliberately a type and an opaque id rather than a foreign key into a database that is not
    -- this one.
    order_reference_type        VARCHAR(64),
    order_reference_id          VARCHAR(128),
    amount                      BIGINT          NOT NULL,
    currency                    VARCHAR(3)      NOT NULL,
    -- The platform's cut, under destination charges. Null for a platform payment, where there is
    -- nobody to take a cut from.
    application_fee_amount      BIGINT,
    -- What Stripe Tax calculated and collected, included in `amount`. Stored because an invoice
    -- has to state it and because it is not the platform's revenue.
    tax_amount                  BIGINT,
    amount_refunded             BIGINT          NOT NULL DEFAULT 0,
    status                      VARCHAR(24)     NOT NULL,
    captured_at                 TIMESTAMPTZ,
    failure_code                VARCHAR(64),
    failure_message             TEXT,
    -- The key this service sent to Stripe when it created the session. Unique, which is what makes
    -- a retried create - a caller's double tap, a timeout, a redelivered order event - resolve to
    -- this row instead of charging somebody twice.
    idempotency_key             VARCHAR(255),
    last_stripe_event_at        TIMESTAMPTZ,
    CONSTRAINT fk_payments_connected_account
        FOREIGN KEY (connected_account_id) REFERENCES connected_accounts (id),
    CONSTRAINT ck_payments_amount CHECK (amount > 0),
    CONSTRAINT ck_payments_currency
        CHECK (currency = upper(currency) AND currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_payments_application_fee CHECK (application_fee_amount IS NULL OR application_fee_amount >= 0),
    -- A fee with nobody to deduct it from is a number that would silently never be charged.
    CONSTRAINT ck_payments_fee_needs_destination
        CHECK (application_fee_amount IS NULL OR connected_account_id IS NOT NULL),
    -- The copied seller columns are filled exactly when there is a seller. Enforced rather than
    -- trusted: a copy that can drift from the thing it was copied from is worse than a join, and
    -- the failure mode - a sale that announces no seller - is silent everywhere else.
    CONSTRAINT ck_payments_seller_mirrors_account
        CHECK ((connected_account_id IS NULL
                AND seller_firebase_uid IS NULL AND seller_stripe_account_id IS NULL)
               OR (connected_account_id IS NOT NULL
                   AND seller_firebase_uid IS NOT NULL AND seller_stripe_account_id IS NOT NULL)),
    CONSTRAINT ck_payments_tax_amount CHECK (tax_amount IS NULL OR tax_amount >= 0),
    -- Refunding more than was charged is not a partial refund, it is a transfer.
    CONSTRAINT ck_payments_amount_refunded
        CHECK (amount_refunded >= 0 AND amount_refunded <= amount),
    CONSTRAINT ck_payments_status
        CHECK (status IN ('REQUIRES_PAYMENT', 'PROCESSING', 'SUCCEEDED', 'CANCELED', 'FAILED')),
    -- Both halves of a reference or neither: a type naming nothing cannot be resolved, and an id
    -- with no type cannot be looked up anywhere.
    CONSTRAINT ck_payments_order_reference_shape
        CHECK ((order_reference_type IS NULL AND order_reference_id IS NULL)
               OR (order_reference_type IS NOT NULL AND order_reference_id IS NOT NULL))
);

-- Money sent back. One row per refund, not per payment: a payment can be refunded in parts, by
-- different people, for different reasons.
CREATE TABLE refunds (
    id                          UUID            PRIMARY KEY,
    created_at                  TIMESTAMPTZ     NOT NULL,
    updated_at                  TIMESTAMPTZ     NOT NULL,
    disabled_at                 TIMESTAMPTZ,
    deleted_at                  TIMESTAMPTZ,
    payment_id                  UUID            NOT NULL,
    stripe_refund_id            VARCHAR(255)    NOT NULL,
    amount                      BIGINT          NOT NULL,
    currency                    VARCHAR(3)      NOT NULL,
    -- Stripe's own reason, free text: they add values, and a CHECK here would reject the webhook
    -- rather than the reason.
    reason                      VARCHAR(64),
    status                      VARCHAR(16)     NOT NULL,
    -- Who decided it. STRIPE_DASHBOARD is the one this service does not originate: a refund made
    -- in Stripe's own dashboard arrives as a webhook with no request behind it, and recording that
    -- as SUPPORT would send somebody looking for an API call that was never made.
    initiated_by                VARCHAR(16)     NOT NULL,
    -- What was clawed back from the connected account, under destination charges. It can be less
    -- than the platform's share of the refund, or nothing at all, if their balance has already been
    -- paid out - which is the standing risk of being merchant of record, and the number that says
    -- how much of it was realised.
    reversed_transfer_amount    BIGINT,
    notes                       TEXT,
    last_stripe_event_at        TIMESTAMPTZ,
    CONSTRAINT fk_refunds_payment FOREIGN KEY (payment_id) REFERENCES payments (id),
    CONSTRAINT uq_refunds_stripe_id UNIQUE (stripe_refund_id),
    CONSTRAINT ck_refunds_amount CHECK (amount > 0),
    CONSTRAINT ck_refunds_currency
        CHECK (currency = upper(currency) AND currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_refunds_status CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED', 'CANCELED')),
    CONSTRAINT ck_refunds_initiated_by
        CHECK (initiated_by IN ('SUPPORT', 'ORDER_EVENT', 'USER_REQUEST', 'STRIPE_DASHBOARD')),
    CONSTRAINT ck_refunds_reversed_transfer_amount
        CHECK (reversed_transfer_amount IS NULL OR reversed_transfer_amount >= 0)
);

-- Stripe's ids, unique where they exist. Partial rather than a plain UNIQUE constraint because
-- both are null until Stripe has created the object - and a plain constraint would still permit
-- duplicates of null, which is correct but means the index covers rows nothing looks up.
CREATE UNIQUE INDEX uq_payments_stripe_payment_intent_id
    ON payments (stripe_payment_intent_id) WHERE stripe_payment_intent_id IS NOT NULL;
CREATE UNIQUE INDEX uq_payments_stripe_checkout_session_id
    ON payments (stripe_checkout_session_id) WHERE stripe_checkout_session_id IS NOT NULL;

-- How a transfer finds the sale it came from, which is the only route Stripe offers: a transfer
-- names a charge and never an intent.
CREATE UNIQUE INDEX uq_payments_stripe_charge_id
    ON payments (stripe_charge_id) WHERE stripe_charge_id IS NOT NULL;

-- What makes a retried create resolve to an existing payment instead of a second charge.
CREATE UNIQUE INDEX uq_payments_idempotency_key
    ON payments (idempotency_key) WHERE idempotency_key IS NOT NULL;

-- "What has this person paid", and its counterpart for a seller: "what have I earned".
CREATE INDEX ix_payments_firebase_uid ON payments (firebase_uid, created_at DESC);
CREATE INDEX ix_payments_connected_account ON payments (connected_account_id, created_at DESC)
    WHERE connected_account_id IS NOT NULL;

-- How the orders service's events find the payment they are about, which is the path a refund on
-- cancellation takes.
CREATE INDEX ix_payments_order_reference ON payments (order_reference_type, order_reference_id)
    WHERE order_reference_id IS NOT NULL;

CREATE INDEX ix_refunds_payment ON refunds (payment_id);

-- The listings filter on these, and every resource row carries one.
CREATE INDEX ix_payments_deleted_at ON payments (deleted_at);
CREATE INDEX ix_refunds_deleted_at ON refunds (deleted_at);
