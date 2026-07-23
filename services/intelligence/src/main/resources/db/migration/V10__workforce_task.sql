-- The digital workforce ledger: one row per CLAIMED task. The open queue is
-- never copied here — it is derived live from the real backlogs (unassigned
-- tickets, unapplied cash), so the queue can't go stale; this table records
-- who worked what, when, with what outcome. Lease semantics on claim: a
-- crashed worker's task frees itself when the lease expires.
CREATE TABLE workforce_task (
    id VARCHAR(200) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    kind VARCHAR(32) NOT NULL,
    subject_ref VARCHAR(128) NOT NULL,
    summary VARCHAR(500),
    status VARCHAR(32) NOT NULL,
    claimed_by VARCHAR(128),
    claimed_at TIMESTAMP WITH TIME ZONE,
    lease_until TIMESTAMP WITH TIME ZONE,
    outcome VARCHAR(500),
    self_tokens INTEGER,
    self_cost_micros BIGINT,
    self_model VARCHAR(64),
    completed_at TIMESTAMP WITH TIME ZONE,
    last_update TIMESTAMP WITH TIME ZONE
);
CREATE INDEX idx_workforce_task_tenant ON workforce_task (tenant_id);
