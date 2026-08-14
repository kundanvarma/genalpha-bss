-- RLS for the mobile-wholesale tables, matching V3/V7 (role usage_app; new tables
-- are already granted via ALTER DEFAULT PRIVILEGES, the GRANT here is explicit).
-- Postgres-only migration (H2 test runs skip this vendor location).
GRANT SELECT, INSERT, UPDATE, DELETE ON wholesale_rate_card TO usage_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON wholesale_usage_ledger TO usage_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON imsi_range TO usage_app;

ALTER TABLE wholesale_rate_card ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON wholesale_rate_card
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));

ALTER TABLE wholesale_usage_ledger ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON wholesale_usage_ledger
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));

ALTER TABLE imsi_range ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON imsi_range
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));
