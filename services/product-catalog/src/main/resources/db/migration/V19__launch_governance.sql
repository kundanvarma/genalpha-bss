-- Intent-to-launch governance. The TMF620 resource stays standard: these
-- columns are the catalog's INTERNAL governance state (who approved, until
-- when, what is held) and never appear on the ProductOffering DTO — the
-- console reads them through the governance door.
ALTER TABLE product_offering ADD COLUMN governance_state VARCHAR(24);
ALTER TABLE product_offering ADD COLUMN governance_json VARCHAR(8000);
ALTER TABLE product_offering ADD COLUMN launch_hold_until TIMESTAMP WITH TIME ZONE;
ALTER TABLE product_offering ADD COLUMN approval_expires_at TIMESTAMP WITH TIME ZONE;

-- The auditable trail: every decision, by whom, and which envelope (if any)
-- made it without a human.
CREATE TABLE governance_ledger (
    id             VARCHAR(36) PRIMARY KEY,
    tenant_id      VARCHAR(64) NOT NULL,
    offering_id    VARCHAR(36) NOT NULL,
    action         VARCHAR(24) NOT NULL,
    actor          VARCHAR(200),
    note           VARCHAR(1000),
    envelope_id    VARCHAR(36),
    envelope_name  VARCHAR(200),
    at             TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_governance_ledger_offering ON governance_ledger (tenant_id, offering_id);
