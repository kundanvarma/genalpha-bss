-- Same tenant wall as the bill table.
GRANT SELECT, INSERT, UPDATE, DELETE ON installment_plan TO billing_app;

ALTER TABLE installment_plan ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON installment_plan
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));
