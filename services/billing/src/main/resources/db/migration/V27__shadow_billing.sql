-- P3 — CONTINUOUS SHADOW BILLING: the industry's one-off parallel bill run,
-- made a standing feature. Every sweep re-prices a slice of the base against
-- TOMORROW's catalog and compares with what the last real bill charged; a
-- mismatch is a drift row raised BEFORE an invoice is wrong.
CREATE TABLE shadow_bill_drift (
    id              VARCHAR(36)  PRIMARY KEY,
    tenant_id       VARCHAR(64)  NOT NULL DEFAULT 'genalpha',
    owner_party_id  VARCHAR(64)  NOT NULL,
    bill_id         VARCHAR(36),
    offering_id     VARCHAR(64),
    offering_name   VARCHAR(255),
    billed_monthly  NUMERIC(14,2) NOT NULL,
    current_monthly NUMERIC(14,2) NOT NULL,
    delta           NUMERIC(14,2) NOT NULL,
    unit            VARCHAR(8),
    detected_at     TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_shadow_drift_tenant ON shadow_bill_drift (tenant_id, detected_at);
