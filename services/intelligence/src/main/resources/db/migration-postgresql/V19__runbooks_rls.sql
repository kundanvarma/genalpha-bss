GRANT SELECT, INSERT, UPDATE, DELETE ON incident_runbook TO intelligence_app;
ALTER TABLE incident_runbook ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON incident_runbook
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));
