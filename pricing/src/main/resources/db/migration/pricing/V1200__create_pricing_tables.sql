-- The catalog: what is for sale on a subscription, and what it costs.
--
-- Versions 1200-1299 belong to the pricing module. It is first of the five because nothing here
-- points outwards - the catalog is this platform's own definition of its tiers, and every other
-- module's tables reference it rather than the other way round.
--
-- The conventions in V1 hold here too: BIGINT minor units, uppercase ISO 4217 with a CHECK that
-- says so, and the five BaseJpaEntity columns on every table.

-- What is for sale on a subscription, as this platform defines it.
--
-- This service owns the catalog and pushes it into Stripe, rather than the other way round: a plan
-- has entitlements and a fee override that mean nothing to Stripe, and a tier nobody can query
-- locally is a tier every other service has to ask Stripe about. `stripe_product_id` is the
-- reconciliation's result and is null until it has run.
CREATE TABLE plans (
    id                          UUID            PRIMARY KEY,
    created_at                  TIMESTAMPTZ     NOT NULL,
    updated_at                  TIMESTAMPTZ     NOT NULL,
    disabled_at                 TIMESTAMPTZ,
    deleted_at                  TIMESTAMPTZ,
    -- The stable name other services and this platform's own code refer to a plan by, e.g.
    -- 'provider_pro'. Lowercase so that a reference written from memory matches.
    key                         VARCHAR(64)     NOT NULL,
    audience                    VARCHAR(16)     NOT NULL,
    name                        VARCHAR(255)    NOT NULL,
    description                 TEXT,
    -- What the plan grants, as a document: the shape differs per plan and per audience, and
    -- changing what a tier includes should not cost a migration.
    entitlements                JSONB           NOT NULL DEFAULT '{}',
    -- Basis points, and the one place the subscription half of the business touches the
    -- marketplace half: a provider on a paid tier can be charged a lower platform fee than the
    -- default. Null means the default applies.
    platform_fee_bps_override   INTEGER,
    stripe_product_id           VARCHAR(255),
    sort_order                  INTEGER         NOT NULL DEFAULT 0,
    CONSTRAINT uq_plans_key UNIQUE (key),
    CONSTRAINT ck_plans_key_lowercase CHECK (key = lower(key)),
    CONSTRAINT ck_plans_audience CHECK (audience IN ('PROVIDER', 'CUSTOMER')),
    CONSTRAINT ck_plans_fee_override_range
        CHECK (platform_fee_bps_override IS NULL OR platform_fee_bps_override BETWEEN 0 AND 10000),
    -- A customer plan has no fee to reduce: customers do not pay the platform fee, sellers do.
    -- Written here because the mistake is silent otherwise - the override would simply never apply,
    -- and the plan would be sold as cheaper than it is.
    CONSTRAINT ck_plans_fee_override_is_provider_only
        CHECK (platform_fee_bps_override IS NULL OR audience = 'PROVIDER')
);

-- What a plan costs, per currency and per billing period.
--
-- A row here, never an UPDATE to one. A Stripe price is immutable, so changing what a plan costs
-- means creating a new price and deactivating the old one - and that is also the honest model:
-- subscriptions already running are on the old price and have to stay queryable.
CREATE TABLE plan_prices (
    id                  UUID            PRIMARY KEY,
    created_at          TIMESTAMPTZ     NOT NULL,
    updated_at          TIMESTAMPTZ     NOT NULL,
    disabled_at         TIMESTAMPTZ,
    deleted_at          TIMESTAMPTZ,
    plan_id             UUID            NOT NULL,
    currency            VARCHAR(3)      NOT NULL,
    unit_amount         BIGINT          NOT NULL,
    billing_interval    VARCHAR(8)      NOT NULL,
    interval_count      INTEGER         NOT NULL DEFAULT 1,
    -- Days free before the first charge, or null for no trial. A trial is not a discount and is
    -- not stored as one: nothing is reduced, the clock simply starts later.
    trial_period_days   INTEGER,
    stripe_price_id     VARCHAR(255),
    active              BOOLEAN         NOT NULL DEFAULT TRUE,
    CONSTRAINT fk_plan_prices_plan FOREIGN KEY (plan_id) REFERENCES plans (id),
    CONSTRAINT ck_plan_prices_currency
        CHECK (currency = upper(currency) AND currency ~ '^[A-Z]{3}$'),
    -- Zero is allowed: a free tier is a real plan, and modelling it as the absence of a price
    -- means every reader has to handle a subscription with nothing to charge.
    CONSTRAINT ck_plan_prices_unit_amount CHECK (unit_amount >= 0),
    CONSTRAINT ck_plan_prices_billing_interval CHECK (billing_interval IN ('MONTH', 'YEAR')),
    CONSTRAINT ck_plan_prices_interval_count CHECK (interval_count >= 1),
    CONSTRAINT ck_plan_prices_trial_period_days
        CHECK (trial_period_days IS NULL OR trial_period_days > 0)
);

-- The reconciliation's own lookups: "does Stripe already have this?". Unique, because two local
-- rows pointing at one Stripe object means one of them is silently not what it claims to be.
CREATE UNIQUE INDEX uq_plans_stripe_product_id
    ON plans (stripe_product_id) WHERE stripe_product_id IS NOT NULL;
CREATE UNIQUE INDEX uq_plan_prices_stripe_price_id
    ON plan_prices (stripe_price_id) WHERE stripe_price_id IS NOT NULL;

-- One active price per plan, currency and period. A second one is not a choice, it is two prices
-- for the same thing and no way to say which was meant.
CREATE UNIQUE INDEX uq_plan_prices_active_shape
    ON plan_prices (plan_id, currency, billing_interval, interval_count) WHERE active;

CREATE INDEX ix_plan_prices_plan ON plan_prices (plan_id);

-- The listings filter on these, and every resource row carries one.
CREATE INDEX ix_plans_deleted_at ON plans (deleted_at);
CREATE INDEX ix_plan_prices_deleted_at ON plan_prices (deleted_at);
