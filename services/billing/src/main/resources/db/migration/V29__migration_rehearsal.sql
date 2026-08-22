-- S6 — MIGRATION REHEARSAL: the parallel bill run pointed at a LEGACY
-- export. Every subscriber row lands on a plan and bills within tolerance,
-- or is an exception BY NAME — the report that de-risks the scariest part
-- of every BSS sale. Reports persist as receipts.
CREATE TABLE migration_rehearsal (
    id          VARCHAR(36)  PRIMARY KEY,
    tenant_id   VARCHAR(64)  NOT NULL DEFAULT 'genalpha',
    name        VARCHAR(255) NOT NULL,
    report_json TEXT         NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_migration_rehearsal ON migration_rehearsal (tenant_id, created_at);
