-- Same second lock as every other domain table.
ALTER TABLE wholesale_access_order ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON wholesale_access_order
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));
