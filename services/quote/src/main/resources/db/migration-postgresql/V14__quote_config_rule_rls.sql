GRANT SELECT, INSERT, UPDATE, DELETE ON quote_config_rule TO quote_app;

ALTER TABLE quote_config_rule ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON quote_config_rule
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));
