-- The open-execution lookup only ever wants unconverted rows; Postgres
-- can index exactly those (H2 cannot, so common V5 keeps a plain index).
DROP INDEX IF EXISTS idx_execution_party_open;
CREATE INDEX idx_execution_party_open ON campaign_execution (tenant_id, party_id)
    WHERE converted_at IS NULL;
