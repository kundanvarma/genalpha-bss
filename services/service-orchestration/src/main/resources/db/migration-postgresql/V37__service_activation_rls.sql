GRANT SELECT, INSERT, UPDATE, DELETE ON service_activation TO som_app;
ALTER TABLE service_activation ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON service_activation
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));

GRANT SELECT, INSERT, UPDATE, DELETE ON service_monitor TO som_app;
ALTER TABLE service_monitor ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON service_monitor
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));
