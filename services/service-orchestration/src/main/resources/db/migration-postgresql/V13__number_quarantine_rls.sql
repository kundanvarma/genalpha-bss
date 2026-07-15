-- Same second lock as every other domain table.
ALTER TABLE number_quarantine ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON number_quarantine
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));
