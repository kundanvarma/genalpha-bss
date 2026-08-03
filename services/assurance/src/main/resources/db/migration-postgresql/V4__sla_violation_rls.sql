GRANT SELECT, INSERT, UPDATE, DELETE ON sla_violation TO assurance_app;
ALTER TABLE sla_violation ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON sla_violation
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));
