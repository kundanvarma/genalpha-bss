-- Same tenant wall as every SOM table.
ALTER TABLE telesales_offer ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON telesales_offer
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));
