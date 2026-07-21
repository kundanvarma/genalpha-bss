-- THE AUDIT BECOMES A CONTROL PLANE: every AI turn now carries what it
-- cost and how it ended, and agent ACTIONS (not just completions) land
-- on the same ledger. The spend index (idx_ai_audit_tenant on
-- tenant_id, created_at) already exists — budget sums ride it.
ALTER TABLE ai_audit ADD COLUMN prompt_tokens     INT;
ALTER TABLE ai_audit ADD COLUMN completion_tokens INT;
ALTER TABLE ai_audit ADD COLUMN cost_micros       BIGINT DEFAULT 0;
ALTER TABLE ai_audit ADD COLUMN tier              VARCHAR(8);
ALTER TABLE ai_audit ADD COLUMN latency_ms        INT;
-- ok | refused-budget | refused-disabled | error
ALTER TABLE ai_audit ADD COLUMN outcome           VARCHAR(24);
-- for agent actions: what was done, and to which resource
ALTER TABLE ai_audit ADD COLUMN action            VARCHAR(64);
ALTER TABLE ai_audit ADD COLUMN resource_ref      VARCHAR(128);

-- THE BUDGET IS OPERATIONAL STATE, not static config: an operator sets a
-- tenant's spend ceiling and can flip AI off entirely. No row = unlimited
-- and enabled (so no tenant is starved by default). Spend is summed from
-- ai_audit over the trailing window; the governor refuses fail-closed
-- when the ceiling is crossed.
CREATE TABLE ai_budget (
    tenant_id     VARCHAR(64) PRIMARY KEY,
    budget_micros BIGINT      NOT NULL DEFAULT 0,   -- 0 = unlimited
    window_hours  INT         NOT NULL DEFAULT 720, -- default: a month
    enabled       BOOLEAN     NOT NULL DEFAULT TRUE,
    last_update   TIMESTAMP WITH TIME ZONE
);
