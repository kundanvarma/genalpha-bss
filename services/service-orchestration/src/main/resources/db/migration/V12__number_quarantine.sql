-- Number change: the released MSISDN goes on the QUARANTINE shelf, never
-- straight back into circulation — the next holder of a number must not
-- inherit the last holder's calls. (The pool counter never re-issues
-- anyway; this table is the auditable story of every released number.)
CREATE TABLE number_quarantine (
    id          VARCHAR(36) PRIMARY KEY,
    tenant_id   VARCHAR(64) NOT NULL,
    number      VARCHAR(64) NOT NULL,
    service_id  VARCHAR(36),
    reason      VARCHAR(32) NOT NULL,
    released_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_quarantine_tenant ON number_quarantine (tenant_id, number);
