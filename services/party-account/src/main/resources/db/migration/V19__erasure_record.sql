-- THE AUDIT OUTLIVES THE ERASED: every executed erasure is a row —
-- who asked, who executed, when, and the full per-category report
-- (deleted counts, retained categories WITH their legal basis). The
-- record itself holds no personal data beyond the party id, which the
-- law expects us to keep as proof the right was honoured.
CREATE TABLE erasure_record (
    id           VARCHAR(64) PRIMARY KEY,
    tenant_id    VARCHAR(64)              NOT NULL,
    party_id     VARCHAR(64)              NOT NULL,
    executed_by  VARCHAR(64)              NOT NULL,
    executed_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    report_json  TEXT                     NOT NULL
);
CREATE INDEX idx_erasure_tenant ON erasure_record (tenant_id, executed_at);
