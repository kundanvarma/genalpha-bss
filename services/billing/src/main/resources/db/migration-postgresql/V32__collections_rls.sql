GRANT SELECT, INSERT, UPDATE, DELETE ON collection_case TO billing_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON dunning_policy TO billing_app;

ALTER TABLE collection_case ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON collection_case
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));

ALTER TABLE dunning_policy ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON dunning_policy
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));
