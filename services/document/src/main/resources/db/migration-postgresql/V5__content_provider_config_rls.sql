-- RLS for content_provider_config, same policy as document: a tenant reads
-- and writes only its own provider row; '__system__' is the escape hatch.
-- The runtime role's table grants come from the default privileges set in
-- V2 (GRANT … ON TABLES), so a new table is covered automatically.
ALTER TABLE content_provider_config ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON content_provider_config
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));
