-- RLS for the usage-policy tables, matching V3/V7/V12 (role usage_app).
-- Postgres-only migration (H2 test runs skip this vendor location).
GRANT SELECT, INSERT, UPDATE, DELETE ON allowance_pool TO usage_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON pool_member TO usage_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON spend_meter TO usage_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON auto_topup_policy TO usage_app;

ALTER TABLE allowance_pool ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON allowance_pool
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));

ALTER TABLE pool_member ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON pool_member
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));

ALTER TABLE spend_meter ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON spend_meter
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));

ALTER TABLE auto_topup_policy ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON auto_topup_policy
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));
