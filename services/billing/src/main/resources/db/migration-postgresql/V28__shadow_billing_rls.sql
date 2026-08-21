-- Same tenant wall as every billing table.
GRANT SELECT, INSERT, UPDATE, DELETE ON shadow_bill_drift TO billing_app;

ALTER TABLE shadow_bill_drift ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON shadow_bill_drift
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));
