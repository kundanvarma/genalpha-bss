-- Same tenant wall as every billing table.
GRANT SELECT, INSERT, UPDATE, DELETE ON billing_run TO billing_app;

ALTER TABLE billing_run ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON billing_run
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));
