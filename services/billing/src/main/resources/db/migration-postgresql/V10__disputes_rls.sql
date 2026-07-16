-- Same tenant wall as the bill table.
GRANT SELECT, INSERT, UPDATE, DELETE ON bill_dispute TO billing_app;

ALTER TABLE bill_dispute ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON bill_dispute
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));
