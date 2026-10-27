-- partner_cost: the second lock, same policy as every tenant table here.
ALTER TABLE partner_cost ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON partner_cost
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));
