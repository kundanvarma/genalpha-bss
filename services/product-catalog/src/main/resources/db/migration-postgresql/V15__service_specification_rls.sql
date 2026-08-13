-- Row-Level Security for the TMF633 service catalog, matching V8. The runtime
-- role catalog_app already gets SELECT/INSERT/UPDATE/DELETE on new tables via the
-- ALTER DEFAULT PRIVILEGES in V8; the explicit GRANT here is belt-and-suspenders.
-- Postgres-only migration (H2 test runs skip this vendor location).
GRANT SELECT, INSERT, UPDATE, DELETE ON service_specification TO catalog_app;

ALTER TABLE service_specification ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON service_specification
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));
