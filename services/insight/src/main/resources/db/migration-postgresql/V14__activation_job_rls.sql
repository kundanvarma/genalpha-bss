-- Row-Level Security for activation jobs — same tenant lock as the rest.
ALTER TABLE activation_job ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_activation_job ON activation_job
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));
