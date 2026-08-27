-- Usage policy: household pools, monetary spend meters (spend cap, content
-- services cap/barring, roaming financial limit), auto top-up consent records
-- and zone-tagged usage for travel passes. The policy layer over the meter
-- primitives — the meters themselves (allowance, boost, record) are untouched
-- except for the zone/validity columns that make a boost a travel pass and
-- the pooled portion that keeps pool-covered GB off the personal meter.

ALTER TABLE usage_record ADD COLUMN zone VARCHAR(64);
ALTER TABLE usage_record ADD COLUMN pooled_value NUMERIC(12, 3);

-- A boost with a zone + validity window IS a travel pass: time-boxed,
-- zone-filtered extra allowance consumed before home meters for zone traffic.
ALTER TABLE allowance_boost ADD COLUMN zone VARCHAR(64);
ALTER TABLE allowance_boost ADD COLUMN valid_from TIMESTAMP WITH TIME ZONE;
ALTER TABLE allowance_boost ADD COLUMN valid_to TIMESTAMP WITH TIME ZONE;

-- One shared bucket per household: the owner (payer) funds it, member
-- subscriptions draw it down through small reserve-then-commit grants.
-- consumed_* are denormalized counters for real-time display, reset lazily
-- when a new cycle's first grant arrives.
CREATE TABLE allowance_pool (
    id               VARCHAR(36)   NOT NULL,
    tenant_id        VARCHAR(64)   NOT NULL DEFAULT 'genalpha',
    name             VARCHAR(255),
    owner_party_id   VARCHAR(64)   NOT NULL,
    usage_spec_name  VARCHAR(128),
    pool_value       NUMERIC(12,3) NOT NULL,
    units            VARCHAR(16)   NOT NULL DEFAULT 'GB',
    consumed_value   NUMERIC(12,3) NOT NULL DEFAULT 0,
    consumed_period  DATE,
    status           VARCHAR(32)   NOT NULL DEFAULT 'active',
    created_at       TIMESTAMP WITH TIME ZONE,
    CONSTRAINT pk_allowance_pool PRIMARY KEY (id)
);
CREATE INDEX idx_pool_owner ON allowance_pool (tenant_id, owner_party_id);

CREATE TABLE pool_member (
    id               VARCHAR(36)   NOT NULL,
    tenant_id        VARCHAR(64)   NOT NULL DEFAULT 'genalpha',
    pool_id          VARCHAR(36)   NOT NULL,
    party_id         VARCHAR(64)   NOT NULL,
    soft_limit_value NUMERIC(12,3),
    hard_limit_value NUMERIC(12,3),
    consumed_value   NUMERIC(12,3) NOT NULL DEFAULT 0,
    consumed_period  DATE,
    status           VARCHAR(32)   NOT NULL DEFAULT 'active',
    created_at       TIMESTAMP WITH TIME ZONE,
    CONSTRAINT pk_pool_member PRIMARY KEY (id),
    CONSTRAINT uq_pool_member UNIQUE (tenant_id, pool_id, party_id)
);
CREATE INDEX idx_pool_member_party ON pool_member (tenant_id, party_id);

-- One monetary-meter primitive, three legal faces (meter_type):
-- 'spend' = subscription spend cap (off by default, customer-settable),
-- 'content' = content-services cap + free barring (statutory floor on the
-- lowest selectable limit), 'roaming' = the default financial limit with
-- warn-at-80 / cut-off-at-100 / explicit continue. Accrual columns are
-- per-cycle, reset lazily; warned_pct/breached dedup the threshold events.
CREATE TABLE spend_meter (
    id               VARCHAR(36)   NOT NULL,
    tenant_id        VARCHAR(64)   NOT NULL DEFAULT 'genalpha',
    party_id         VARCHAR(64)   NOT NULL,
    meter_type       VARCHAR(16)   NOT NULL,
    enabled          BOOLEAN       NOT NULL DEFAULT FALSE,
    barred           BOOLEAN       NOT NULL DEFAULT FALSE,
    limit_value      NUMERIC(12,2),
    currency         VARCHAR(8),
    notify_at_pct    NUMERIC(5,2)  NOT NULL DEFAULT 80,
    block_on_breach  BOOLEAN       NOT NULL DEFAULT TRUE,
    accrued_value    NUMERIC(12,2) NOT NULL DEFAULT 0,
    accrual_period   DATE,
    warned_pct       NUMERIC(5,2),
    breached         BOOLEAN       NOT NULL DEFAULT FALSE,
    blocked          BOOLEAN       NOT NULL DEFAULT FALSE,
    continue_elected BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at       TIMESTAMP WITH TIME ZONE,
    updated_at       TIMESTAMP WITH TIME ZONE,
    CONSTRAINT pk_spend_meter PRIMARY KEY (id),
    CONSTRAINT uq_spend_meter UNIQUE (tenant_id, party_id, meter_type)
);

-- Auto top-up is opt-in ONLY: consent_at is part of the record, never
-- defaulted. Caps bound the per-cycle boost count and spend.
CREATE TABLE auto_topup_policy (
    id                  VARCHAR(36)   NOT NULL,
    tenant_id           VARCHAR(64)   NOT NULL DEFAULT 'genalpha',
    party_id            VARCHAR(64)   NOT NULL,
    enabled             BOOLEAN       NOT NULL DEFAULT FALSE,
    boost_offering_id   VARCHAR(64),
    trigger_type        VARCHAR(16)   NOT NULL DEFAULT 'depletion',
    trigger_pct         NUMERIC(5,2),
    max_boosts_per_cycle INTEGER      NOT NULL DEFAULT 1,
    max_spend_per_cycle NUMERIC(12,2),
    consent_at          TIMESTAMP WITH TIME ZONE,
    created_at          TIMESTAMP WITH TIME ZONE,
    updated_at          TIMESTAMP WITH TIME ZONE,
    CONSTRAINT pk_auto_topup_policy PRIMARY KEY (id),
    CONSTRAINT uq_auto_topup_party UNIQUE (tenant_id, party_id)
);
