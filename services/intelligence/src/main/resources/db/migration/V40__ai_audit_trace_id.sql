-- #422: the W3C trace id of the call (32 hex). One key joins a ledger row to
-- its trace in a collector and to the ontology receipt / decision-log row of
-- the same request. Nullable: rows written before tracing, or with tracing
-- absent, have none. A new column on a table that already has its row-level
-- security policy (V2) is covered by that policy; no new table, no new RLS.
ALTER TABLE ai_audit ADD COLUMN trace_id VARCHAR(32);
CREATE INDEX idx_ai_audit_trace ON ai_audit (tenant_id, trace_id);
