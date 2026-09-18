-- Row-Level Security for the TMF633 serviceCandidate and import/export job
-- tables, matching V8/V15. catalog_app already gets the DML grants via the
-- ALTER DEFAULT PRIVILEGES in V8; the explicit GRANT is belt-and-suspenders.
-- Postgres-only migration (H2 test runs skip this vendor location).
GRANT SELECT, INSERT, UPDATE, DELETE ON service_candidate TO catalog_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON service_catalog_job TO catalog_app;

ALTER TABLE service_candidate ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON service_candidate
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));

ALTER TABLE service_catalog_job ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON service_catalog_job
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));
