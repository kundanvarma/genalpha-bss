-- The tail: volume pricing, quote e-signature, team roll-up, pipeline snapshot.

-- Volume pricing tiers: buy at least min_quantity of an offering → a line
-- discount. (An optional segment scopes a rule to a customer segment.)
CREATE TABLE quote_pricing_rule (
    id               VARCHAR(36)  NOT NULL PRIMARY KEY,
    tenant_id        VARCHAR(64)  NOT NULL,
    offering_name    VARCHAR(255) NOT NULL,
    min_quantity     INTEGER      NOT NULL DEFAULT 1,
    segment          VARCHAR(64),
    discount_percent NUMERIC(5,2) NOT NULL,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_quote_pricing_rule ON quote_pricing_rule (tenant_id, offering_name);

-- Quote e-signature seam.
ALTER TABLE quote ADD COLUMN signature_status VARCHAR(16) NOT NULL DEFAULT 'unsigned';
ALTER TABLE quote ADD COLUMN signed_by        VARCHAR(255);
ALTER TABLE quote ADD COLUMN signed_at        TIMESTAMP WITH TIME ZONE;

-- Quota owners roll up to a team.
ALTER TABLE sales_quota ADD COLUMN team VARCHAR(128);

-- Weekly pipeline snapshot — the open weighted forecast captured over time, so
-- forecast-over-time and slippage are visible.
CREATE TABLE pipeline_snapshot (
    id                 VARCHAR(36)   NOT NULL PRIMARY KEY,
    tenant_id          VARCHAR(64)   NOT NULL,
    captured_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    open_count         INTEGER       NOT NULL,
    open_amount        NUMERIC(14,2) NOT NULL,
    weighted_forecast  NUMERIC(14,2) NOT NULL,
    currency           VARCHAR(8)    NOT NULL
);
CREATE INDEX idx_pipeline_snapshot ON pipeline_snapshot (tenant_id, captured_at);
