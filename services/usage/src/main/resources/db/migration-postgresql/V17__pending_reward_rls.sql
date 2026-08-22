GRANT SELECT, INSERT, UPDATE, DELETE ON pending_data_reward TO usage_app;

ALTER TABLE pending_data_reward ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON pending_data_reward
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));
