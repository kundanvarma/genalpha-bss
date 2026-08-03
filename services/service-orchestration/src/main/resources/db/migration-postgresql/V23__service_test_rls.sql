GRANT SELECT, INSERT, UPDATE, DELETE ON service_test TO som_app;
ALTER TABLE service_test ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON service_test
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));
