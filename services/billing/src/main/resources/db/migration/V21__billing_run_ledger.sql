-- THE RUN HAS A FACE: each billing run is a row — started, finished,
-- how many accounts, how many bills, what failed. A crashed run leaves
-- its 'running' row behind as evidence; the next run marks it
-- superseded and continues where the bills already cut left off.
CREATE TABLE billing_run (
    id             VARCHAR(64) PRIMARY KEY,
    tenant_id      VARCHAR(64)              NOT NULL,
    status         VARCHAR(20)              NOT NULL,
    started_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    finished_at    TIMESTAMP WITH TIME ZONE,
    accounts_total INT                      NOT NULL DEFAULT 0,
    bills_created  INT                      NOT NULL DEFAULT 0,
    skipped        INT                      NOT NULL DEFAULT 0,
    failed         INT                      NOT NULL DEFAULT 0,
    last_error     VARCHAR(500)
);
CREATE INDEX idx_billing_run_tenant ON billing_run (tenant_id, started_at);
