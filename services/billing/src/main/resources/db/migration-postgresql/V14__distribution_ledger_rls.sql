-- Same tenant wall as every billing table.
GRANT SELECT, INSERT, UPDATE, DELETE ON bill_distribution TO billing_app;

ALTER TABLE bill_distribution ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON bill_distribution
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));
