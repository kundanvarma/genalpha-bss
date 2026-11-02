-- #422: the W3C trace id (32 hex) of the execute that wrote the row. The same
-- id rides in the decision's `context.traceId` to insight's decision log and
-- on intelligence's AI ledger, so one key joins a trace, a receipt and a
-- decision. Nullable: earlier rows, and rows written with tracing absent,
-- have none. It is NOT part of the hash chain (ReceiptStore.canonical): a
-- pointer to telemetry is not evidence, and rows hashed before this column
-- existed must stay verifiable. The table's RLS policy (V2) already covers it.
ALTER TABLE action_receipt ADD COLUMN trace_id VARCHAR(32);
CREATE INDEX idx_action_receipt_trace ON action_receipt (tenant_id, trace_id);
