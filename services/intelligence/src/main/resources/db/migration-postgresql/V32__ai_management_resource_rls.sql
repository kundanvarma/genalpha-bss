-- Same tenant wall as ai_contract / ai_audit.
GRANT SELECT, INSERT, UPDATE, DELETE ON ai_management_resource TO intelligence_app;

ALTER TABLE ai_management_resource ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON ai_management_resource
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));
