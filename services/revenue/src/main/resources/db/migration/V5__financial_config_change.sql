-- BR-9: a change to live financial configuration is a thing with a life of
-- its own — drafted, validated, approved, activated — so that the chart of
-- accounts cannot be edited into a hole between one keystroke and the next.
--
-- The row keeps BOTH sides: what the account said when the change was drafted
-- and what it is being asked to say. Activation compares the "current" columns
-- against the live row and refuses when somebody else moved it meanwhile, so a
-- stale draft cannot quietly undo a colleague's change.
CREATE TABLE financial_config_change (
    id                VARCHAR(36)  PRIMARY KEY,
    tenant_id         VARCHAR(64)  NOT NULL,
    posting_key       VARCHAR(120) NOT NULL,
    proposed_code     VARCHAR(32),
    proposed_name     VARCHAR(200),
    proposed_value    NUMERIC(14,6),
    current_code      VARCHAR(32),
    current_name      VARCHAR(200),
    current_value     NUMERIC(14,6),
    reason            VARCHAR(500),
    state             VARCHAR(24)  NOT NULL,
    findings          VARCHAR(2000),
    postings_using    BIGINT       NOT NULL DEFAULT 0,
    drafted_by        VARCHAR(160) NOT NULL,
    drafted_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    validated_by      VARCHAR(160),
    validated_at      TIMESTAMP WITH TIME ZONE,
    approved_by       VARCHAR(160),
    approved_at       TIMESTAMP WITH TIME ZONE,
    activated_by      VARCHAR(160),
    activated_at      TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_fin_config_change_tenant ON financial_config_change (tenant_id, state);
