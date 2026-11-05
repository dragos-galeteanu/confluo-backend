-- Recurring money: what somebody subscribed to, what they were billed, and what was taken off.
--
-- Versions 1600-1699 belong to the billing module. It runs last because it reaches furthest:
-- subscriptions reference pricing's plan_prices (V1200) and discount_redemptions reference
-- marketplace's payments (V1400). Both are plain id columns in the entities.
--
-- The order WITHIN this file matters as much as the order between files: discounts before
-- subscriptions, subscriptions before invoices, and discount_redemptions last, because each
-- references the ones above it.
--
-- The conventions in V1 hold here too: BIGINT minor units, uppercase ISO 4217 with a CHECK that
-- says so, and the five BaseJpaEntity columns on every table.

-- Money off, however it came to be granted.
--
-- One table for the four ways somebody gets a discount, because what a discount IS does not differ
-- between them - only how it is reached. A public code is typed in; a referral, a goodwill credit
-- and a cohort grant are attached to one person by this platform, which is why `code` is nullable:
-- giving a goodwill credit a code would make it redeemable by anyone who learned the string.
--
-- The CHECK constraints below are what make a discount either a percentage or an amount and never
-- both, and what make a repeating discount state its length. A service enforcing that alone would
-- be one refactor away from a coupon that reduces nothing, and Stripe would reject it at redemption
-- - in front of the payer, at checkout.
CREATE TABLE discounts (
    id                              UUID            PRIMARY KEY,
    created_at                      TIMESTAMPTZ     NOT NULL,
    updated_at                      TIMESTAMPTZ     NOT NULL,
    disabled_at                     TIMESTAMPTZ,
    deleted_at                      TIMESTAMPTZ,
    -- Uppercase, because Stripe matches promotion codes case-insensitively and a column that
    -- stores both WELCOME10 and welcome10 has two rows for one code and finds one of them.
    code                            VARCHAR(64),
    grant_kind                      VARCHAR(16)     NOT NULL,
    kind                            VARCHAR(16)     NOT NULL,
    -- Up to two decimal places, which is Stripe's limit. NUMERIC and not a floating type for the
    -- same reason the amounts are integers.
    percent_off                     NUMERIC(5,2),
    amount_off                      BIGINT,
    currency                        VARCHAR(3),
    duration                        VARCHAR(16)     NOT NULL,
    duration_in_months              INTEGER,
    -- Across everybody. Null means unlimited.
    max_redemptions                 INTEGER,
    -- Per person, which Stripe's own coupon cannot express - it is enforced here, against
    -- discount_redemptions, before a session is created.
    max_redemptions_per_customer    INTEGER,
    valid_from                      TIMESTAMPTZ,
    valid_until                     TIMESTAMPTZ,
    minimum_amount                  BIGINT,
    minimum_amount_currency         VARCHAR(3),
    first_time_transaction_only     BOOLEAN         NOT NULL DEFAULT FALSE,
    -- Which plans this may be used against, as a JSON array of plan ids. Empty means any of them.
    applies_to_plan_ids             JSONB           NOT NULL DEFAULT '[]',
    -- The reconciliation's results. A coupon is the shape of the discount; a promotion code is the
    -- string a payer can type, and only a redeemable discount has one.
    stripe_coupon_id                VARCHAR(255),
    stripe_promotion_code_id        VARCHAR(255),
    CONSTRAINT ck_discounts_code_uppercase CHECK (code IS NULL OR code = upper(code)),
    CONSTRAINT ck_discounts_grant_kind
        CHECK (grant_kind IN ('PUBLIC_CODE', 'REFERRAL', 'GOODWILL', 'COHORT')),
    CONSTRAINT ck_discounts_kind CHECK (kind IN ('PERCENTAGE', 'FIXED')),
    CONSTRAINT ck_discounts_duration CHECK (duration IN ('ONCE', 'REPEATING', 'FOREVER')),
    -- A public code with nothing to type is unreachable.
    CONSTRAINT ck_discounts_public_code_has_code
        CHECK (grant_kind <> 'PUBLIC_CODE' OR code IS NOT NULL),
    -- Exactly one of the two shapes, with exactly the columns that shape needs. Ten per cent needs
    -- no currency; ten off is a different discount in every currency and is meaningless without one.
    CONSTRAINT ck_discounts_percentage_shape
        CHECK (kind <> 'PERCENTAGE'
               OR (percent_off IS NOT NULL AND amount_off IS NULL AND currency IS NULL)),
    CONSTRAINT ck_discounts_fixed_shape
        CHECK (kind <> 'FIXED'
               OR (amount_off IS NOT NULL AND currency IS NOT NULL AND percent_off IS NULL)),
    CONSTRAINT ck_discounts_percent_off_range
        CHECK (percent_off IS NULL OR (percent_off > 0 AND percent_off <= 100)),
    CONSTRAINT ck_discounts_amount_off CHECK (amount_off IS NULL OR amount_off > 0),
    CONSTRAINT ck_discounts_currency
        CHECK (currency IS NULL OR (currency = upper(currency) AND currency ~ '^[A-Z]{3}$')),
    -- A length only means something for a repeating discount, and a repeating one without a length
    -- has no end.
    CONSTRAINT ck_discounts_duration_in_months
        CHECK ((duration = 'REPEATING' AND duration_in_months IS NOT NULL AND duration_in_months > 0)
               OR (duration <> 'REPEATING' AND duration_in_months IS NULL)),
    CONSTRAINT ck_discounts_max_redemptions
        CHECK (max_redemptions IS NULL OR max_redemptions > 0),
    CONSTRAINT ck_discounts_max_redemptions_per_customer
        CHECK (max_redemptions_per_customer IS NULL OR max_redemptions_per_customer > 0),
    CONSTRAINT ck_discounts_validity_window
        CHECK (valid_from IS NULL OR valid_until IS NULL OR valid_until > valid_from),
    -- A threshold and its currency are one fact in two columns, so neither is allowed alone.
    CONSTRAINT ck_discounts_minimum_amount_shape
        CHECK ((minimum_amount IS NULL AND minimum_amount_currency IS NULL)
               OR (minimum_amount IS NOT NULL AND minimum_amount > 0
                   AND minimum_amount_currency IS NOT NULL
                   AND minimum_amount_currency = upper(minimum_amount_currency)))
);

-- What somebody is subscribed to.
--
-- Stripe Billing owns this state machine outright: the renewal clock, the retry schedule behind
-- PAST_DUE, and the move to UNPAID are all theirs, and every column below is written by a webhook.
-- A second implementation of any of it here would be a second answer to whether somebody has paid.
--
-- No uniqueness on firebase_uid. One person can hold a provider plan and a customer plan at the
-- same time - both sides of the marketplace subscribe - and they are two subscriptions, not a
-- conflict.
CREATE TABLE subscriptions (
    id                      UUID            PRIMARY KEY,
    created_at              TIMESTAMPTZ     NOT NULL,
    updated_at              TIMESTAMPTZ     NOT NULL,
    disabled_at             TIMESTAMPTZ,
    deleted_at              TIMESTAMPTZ,
    firebase_uid            VARCHAR(128)    NOT NULL,
    stripe_subscription_id  VARCHAR(255)    NOT NULL,
    -- The price in force, which is a row rather than a plan because a plan's price changes by
    -- adding one: this says what they are actually paying, not what the tier costs today.
    plan_price_id           UUID            NOT NULL,
    discount_id             UUID,
    status                  VARCHAR(24)     NOT NULL,
    current_period_start    TIMESTAMPTZ,
    current_period_end      TIMESTAMPTZ,
    cancel_at_period_end    BOOLEAN         NOT NULL DEFAULT FALSE,
    canceled_at             TIMESTAMPTZ,
    trial_end               TIMESTAMPTZ,
    last_stripe_event_at    TIMESTAMPTZ,
    CONSTRAINT fk_subscriptions_plan_price FOREIGN KEY (plan_price_id) REFERENCES plan_prices (id),
    CONSTRAINT fk_subscriptions_discount FOREIGN KEY (discount_id) REFERENCES discounts (id),
    CONSTRAINT uq_subscriptions_stripe_id UNIQUE (stripe_subscription_id),
    CONSTRAINT ck_subscriptions_status
        CHECK (status IN ('INCOMPLETE', 'INCOMPLETE_EXPIRED', 'TRIALING', 'ACTIVE',
                          'PAST_DUE', 'UNPAID', 'CANCELED', 'PAUSED')),
    CONSTRAINT ck_subscriptions_period
        CHECK (current_period_start IS NULL OR current_period_end IS NULL
               OR current_period_end > current_period_start)
);

-- What was billed, per cycle. The queryable billing history, and what a receipt is rendered from.
--
-- `subscription_id` is nullable because not every invoice belongs to a subscription: a proration
-- charged immediately on an upgrade is its own invoice, and so is anything billed out of band.
CREATE TABLE invoices (
    id                      UUID            PRIMARY KEY,
    created_at              TIMESTAMPTZ     NOT NULL,
    updated_at              TIMESTAMPTZ     NOT NULL,
    disabled_at             TIMESTAMPTZ,
    deleted_at              TIMESTAMPTZ,
    stripe_invoice_id       VARCHAR(255)    NOT NULL,
    subscription_id         UUID,
    firebase_uid            VARCHAR(128)    NOT NULL,
    -- Stripe's human-readable number, which is what a person quotes when they ask about a charge.
    -- Null on a draft, because Stripe assigns it at finalisation.
    number                  VARCHAR(64),
    status                  VARCHAR(16)     NOT NULL,
    amount_due              BIGINT          NOT NULL DEFAULT 0,
    amount_paid             BIGINT          NOT NULL DEFAULT 0,
    tax_amount              BIGINT          NOT NULL DEFAULT 0,
    currency                VARCHAR(3)      NOT NULL,
    period_start            TIMESTAMPTZ,
    period_end              TIMESTAMPTZ,
    -- Stripe's own hosted copies. Kept as URLs rather than fetched and stored: they are generated,
    -- they stay valid, and a PDF in this database is a PDF to keep in step with a corrected invoice.
    hosted_invoice_url      TEXT,
    invoice_pdf             TEXT,
    -- How many times Stripe has tried to collect, and when it will next try. Both are Stripe's and
    -- both are what a dunning message is worded from.
    attempt_count           INTEGER         NOT NULL DEFAULT 0,
    next_payment_attempt    TIMESTAMPTZ,
    last_stripe_event_at    TIMESTAMPTZ,
    CONSTRAINT fk_invoices_subscription FOREIGN KEY (subscription_id) REFERENCES subscriptions (id),
    CONSTRAINT uq_invoices_stripe_id UNIQUE (stripe_invoice_id),
    CONSTRAINT ck_invoices_status CHECK (status IN ('DRAFT', 'OPEN', 'PAID', 'UNCOLLECTIBLE', 'VOID')),
    CONSTRAINT ck_invoices_currency
        CHECK (currency = upper(currency) AND currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_invoices_amounts
        CHECK (amount_due >= 0 AND amount_paid >= 0 AND tax_amount >= 0),
    CONSTRAINT ck_invoices_attempt_count CHECK (attempt_count >= 0),
    CONSTRAINT ck_invoices_period
        CHECK (period_start IS NULL OR period_end IS NULL OR period_end >= period_start)
);

-- Who has used which discount. The per-person limit lives here.
--
-- Stripe's coupon can cap total redemptions and cannot cap them per customer, so that rule is
-- enforced against this table before a session is created. Keyed to the uid rather than to the
-- Stripe customer, because the limit is about a person and a person can be given a new Stripe
-- customer by any number of mistakes.
CREATE TABLE discount_redemptions (
    id                  UUID            PRIMARY KEY,
    created_at          TIMESTAMPTZ     NOT NULL,
    updated_at          TIMESTAMPTZ     NOT NULL,
    disabled_at         TIMESTAMPTZ,
    deleted_at          TIMESTAMPTZ,
    discount_id         UUID            NOT NULL,
    firebase_uid        VARCHAR(128)    NOT NULL,
    -- Exactly one of these two. A discount is redeemed against a subscription or against a single
    -- payment, and which one it was is the only way to find what it actually reduced.
    subscription_id     UUID,
    payment_id          UUID,
    redeemed_at         TIMESTAMPTZ     NOT NULL,
    CONSTRAINT fk_discount_redemptions_discount FOREIGN KEY (discount_id) REFERENCES discounts (id),
    CONSTRAINT fk_discount_redemptions_subscription
        FOREIGN KEY (subscription_id) REFERENCES subscriptions (id),
    CONSTRAINT fk_discount_redemptions_payment FOREIGN KEY (payment_id) REFERENCES payments (id),
    CONSTRAINT ck_discount_redemptions_one_target
        CHECK ((subscription_id IS NOT NULL AND payment_id IS NULL)
               OR (subscription_id IS NULL AND payment_id IS NOT NULL))
);

-- One code, one discount. Partial because granted discounts have no code.
CREATE UNIQUE INDEX uq_discounts_code ON discounts (code) WHERE code IS NOT NULL;

-- The reconciliation's own lookups, as in the catalog: a coupon is the shape of the discount, a
-- promotion code is the string a payer can type, and two local rows pointing at either means one
-- of them is silently not what it claims to be.
CREATE UNIQUE INDEX uq_discounts_stripe_coupon_id
    ON discounts (stripe_coupon_id) WHERE stripe_coupon_id IS NOT NULL;
CREATE UNIQUE INDEX uq_discounts_stripe_promotion_code_id
    ON discounts (stripe_promotion_code_id) WHERE stripe_promotion_code_id IS NOT NULL;

-- The dunning sweep's only query. Partial, because the rows it wants are the rare ones.
CREATE INDEX ix_subscriptions_collection ON subscriptions (current_period_end)
    WHERE status IN ('PAST_DUE', 'UNPAID');

CREATE INDEX ix_subscriptions_firebase_uid ON subscriptions (firebase_uid);
CREATE INDEX ix_invoices_subscription ON invoices (subscription_id, period_start DESC);
CREATE INDEX ix_invoices_firebase_uid ON invoices (firebase_uid, created_at DESC);

-- The per-person redemption limit's own lookup.
CREATE INDEX ix_discount_redemptions_scope ON discount_redemptions (discount_id, firebase_uid);

-- The listings filter on these, and every resource row carries one.
CREATE INDEX ix_discounts_deleted_at ON discounts (deleted_at);
CREATE INDEX ix_discount_redemptions_deleted_at ON discount_redemptions (deleted_at);
CREATE INDEX ix_subscriptions_deleted_at ON subscriptions (deleted_at);
CREATE INDEX ix_invoices_deleted_at ON invoices (deleted_at);
