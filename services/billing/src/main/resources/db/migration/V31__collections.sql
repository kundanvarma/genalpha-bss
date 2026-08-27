-- COLLECTIONS: the ladder between a missed payment and a lost customer.
-- One case per financial account aggregates every overdue bill; the tenant's
-- dunning policy (ordered steps over aging) drives it, and a country
-- statutory pack it cannot undercut sits underneath (Norway: purregebyr only
-- >= 14 days after due, fee cap, max 2 fee-bearing reminders, one month
-- between demand+warning and any restriction, minimum actionable amount).
CREATE TABLE collection_case (
    id                    VARCHAR(36)  PRIMARY KEY,
    tenant_id             VARCHAR(64)  NOT NULL,
    account_id            VARCHAR(64)  NOT NULL,
    state                 VARCHAR(16)  NOT NULL,
    currency              VARCHAR(8),
    overdue_value         NUMERIC(12,2) NOT NULL DEFAULT 0,
    oldest_due_at         TIMESTAMP WITH TIME ZONE,
    step_index            INT NOT NULL DEFAULT 0,
    fee_count             INT NOT NULL DEFAULT 0,
    warned_at             TIMESTAMP WITH TIME ZONE,
    promise_value         NUMERIC(12,2),
    promise_due_at        TIMESTAMP WITH TIME ZONE,
    promises_made         INT NOT NULL DEFAULT 0,
    promise_window_start  TIMESTAMP WITH TIME ZONE,
    dispute_hold_value    NUMERIC(12,2),
    hardship_hold         BOOLEAN NOT NULL DEFAULT FALSE,
    enforced_services     VARCHAR(2000),
    cured_at              TIMESTAMP WITH TIME ZONE,
    written_off_at        TIMESTAMP WITH TIME ZONE,
    write_off_reason      VARCHAR(500),
    created_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    last_update           TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_collection_case_account UNIQUE (tenant_id, account_id)
);
CREATE INDEX idx_collection_case_state ON collection_case (tenant_id, state);

CREATE TABLE dunning_policy (
    id                     VARCHAR(36)  PRIMARY KEY,
    tenant_id              VARCHAR(64)  NOT NULL,
    name                   VARCHAR(255) NOT NULL,
    country                VARCHAR(2)   NOT NULL,
    payment_term_days      INT NOT NULL DEFAULT 14,
    entry_threshold        NUMERIC(12,2) NOT NULL,
    currency               VARCHAR(8),
    steps_json             VARCHAR(4000) NOT NULL,
    reconnection_fee       NUMERIC(12,2) NOT NULL DEFAULT 0,
    write_off_threshold    NUMERIC(12,2) NOT NULL DEFAULT 0,
    promise_max_per_period INT NOT NULL DEFAULT 2,
    promise_period_days    INT NOT NULL DEFAULT 90,
    promise_max_days       INT NOT NULL DEFAULT 14,
    auto_refund_threshold  NUMERIC(12,2) NOT NULL DEFAULT 0,
    active                 BOOLEAN NOT NULL DEFAULT TRUE,
    created_at             TIMESTAMP WITH TIME ZONE NOT NULL,
    last_update            TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_dunning_policy_tenant ON dunning_policy (tenant_id, active);

-- A rate line may now exist BEFORE its bill: collections' reconnection fee
-- is minted standalone (bill_id NULL, isBilled=false) and the next billing
-- run adopts it onto the bill it cuts.
ALTER TABLE applied_billing_rate ALTER COLUMN bill_id DROP NOT NULL;
