-- Data top-ups: an allowance row flagged boost=true belongs to a one-time
-- top-up offering. Buying one records a boost for the buyer's current period;
-- meters and overage rating add boosts on top of the plan's base allowance.
ALTER TABLE usage_allowance ADD COLUMN boost BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE allowance_boost (
    id               VARCHAR(36) PRIMARY KEY,
    tenant_id        VARCHAR(64) NOT NULL DEFAULT 'genalpha',
    owner_party_id   VARCHAR(64) NOT NULL,
    usage_spec_name  VARCHAR(128) NOT NULL,
    boost_value      NUMERIC(12,3) NOT NULL,
    units            VARCHAR(16),
    period_start     DATE NOT NULL,
    product_order_id VARCHAR(64) NOT NULL,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_boost_per_order UNIQUE (tenant_id, product_order_id, usage_spec_name)
);
CREATE INDEX idx_allowance_boost_party ON allowance_boost (tenant_id, owner_party_id, period_start);
