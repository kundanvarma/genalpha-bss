-- Materialized membership: freeze an audience's resolved members into a snapshot
-- so hot audiences read instantly (no recompute) and membership is stable
-- between refreshes — the scale tier above live set-based resolution.
CREATE TABLE audience_snapshot (
    id          VARCHAR(36) NOT NULL,
    tenant_id   VARCHAR(64) NOT NULL DEFAULT 'genalpha',
    audience_id VARCHAR(36) NOT NULL,
    party_id    VARCHAR(64),
    email       VARCHAR(255),
    created_at  TIMESTAMP WITH TIME ZONE,
    CONSTRAINT pk_audience_snapshot PRIMARY KEY (id)
);
CREATE INDEX idx_audience_snapshot_aud ON audience_snapshot (tenant_id, audience_id);

ALTER TABLE audience ADD COLUMN materialized_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE audience ADD COLUMN member_count INTEGER;
