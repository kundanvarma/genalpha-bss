-- Row-Level Security for the TMF634 resource catalog, matching V15 (the
-- service catalog) and V8. A new table is unprotected by default — this is
-- the wall; ops/security/rls_check.py proves it against the live catalogue.
-- Postgres-only migration (H2 test runs skip this vendor location).
GRANT SELECT, INSERT, UPDATE, DELETE ON resource_specification TO catalog_app;

ALTER TABLE resource_specification ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON resource_specification
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));
