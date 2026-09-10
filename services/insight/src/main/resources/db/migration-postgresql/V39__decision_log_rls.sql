-- RLS for the decision log, same policy as every insight table.
ALTER TABLE decision_log ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON decision_log
    USING (current_setting('app.tenant_id', true) = '__system__' OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__' OR tenant_id = current_setting('app.tenant_id', true));
