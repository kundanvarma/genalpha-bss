-- Approvals are tenant data: RLS double-lock like every other row.
GRANT SELECT, INSERT, UPDATE, DELETE ON workforce_approval TO intelligence_app;

ALTER TABLE workforce_approval ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON workforce_approval
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));
