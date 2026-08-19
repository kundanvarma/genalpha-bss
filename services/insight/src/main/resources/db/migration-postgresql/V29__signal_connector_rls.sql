-- RLS for signal connectors, same policy as every insight table.
ALTER TABLE signal_connector ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON signal_connector
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));
