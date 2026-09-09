-- Row-Level Security for the governance ledger, matching V8/V15.
-- Postgres-only migration (H2 test runs skip this vendor location).
GRANT SELECT, INSERT, UPDATE, DELETE ON governance_ledger TO catalog_app;

ALTER TABLE governance_ledger ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON governance_ledger
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));
