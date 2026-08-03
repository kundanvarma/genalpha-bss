-- TMF915: the per-scenario brake the control plane never had. The tenant
-- kill-switch stops ALL AI; an ai_contract row suspends ONE scenario (model
-- contract) while the rest of the fleet keeps working. No row = active.
CREATE TABLE ai_contract (
    id          VARCHAR(36)  NOT NULL,
    tenant_id   VARCHAR(64)  NOT NULL,
    use_case    VARCHAR(64)  NOT NULL,
    enabled     BOOLEAN      NOT NULL DEFAULT TRUE,
    note        VARCHAR(500),
    decided_at  TIMESTAMP WITH TIME ZONE,
    last_update TIMESTAMP WITH TIME ZONE,
    CONSTRAINT pk_ai_contract PRIMARY KEY (id),
    CONSTRAINT uq_ai_contract UNIQUE (tenant_id, use_case)
);
CREATE INDEX idx_ai_contract_tenant ON ai_contract (tenant_id);
