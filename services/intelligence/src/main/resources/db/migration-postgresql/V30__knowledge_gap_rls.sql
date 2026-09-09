-- Same tenant wall as ai_audit / ai_budget.
GRANT SELECT, INSERT, UPDATE, DELETE ON knowledge_gap TO intelligence_app;

ALTER TABLE knowledge_gap ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON knowledge_gap
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));
