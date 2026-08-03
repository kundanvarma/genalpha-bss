-- RLS for partnership_type, same policy as agreement.
ALTER TABLE partnership_type ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON partnership_type
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));
