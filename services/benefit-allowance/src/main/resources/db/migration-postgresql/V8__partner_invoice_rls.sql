-- partner_invoice: the second lock, same policy as every tenant table here.
ALTER TABLE partner_invoice ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON partner_invoice
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));
