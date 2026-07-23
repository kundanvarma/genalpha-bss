-- The T3 gate: high-blast-radius actions (refunds, cease, erasure) a
-- digital worker may PROPOSE but never perform. The row stores the action
-- as data; a human approves and the action executes with the APPROVER'S
-- token — the worker never writes what a human must own.
CREATE TABLE workforce_approval (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    requested_by VARCHAR(128) NOT NULL,
    action VARCHAR(64) NOT NULL,
    method VARCHAR(8) NOT NULL,
    path VARCHAR(300) NOT NULL,
    body_json VARCHAR(4000),
    reason VARCHAR(500),
    status VARCHAR(16) NOT NULL,
    decided_by VARCHAR(128),
    decision_note VARCHAR(500),
    result_json VARCHAR(2000),
    created_at TIMESTAMP WITH TIME ZONE,
    decided_at TIMESTAMP WITH TIME ZONE
);
CREATE INDEX idx_workforce_approval_tenant ON workforce_approval (tenant_id);
