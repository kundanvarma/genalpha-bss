-- RLS for risk_assessment, same policy as every tenant-scoped table here.
ALTER TABLE risk_assessment ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON risk_assessment
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));
