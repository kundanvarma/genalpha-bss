GRANT SELECT, INSERT, UPDATE, DELETE ON opportunity_stage_history TO quote_app;

ALTER TABLE opportunity_stage_history ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON opportunity_stage_history
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));
