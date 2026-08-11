-- RLS for carrier_config, same policy as the fulfilment tables. Runtime role
-- grants come from V2's default privileges, so a new table is covered.
ALTER TABLE carrier_config ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON carrier_config
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));
