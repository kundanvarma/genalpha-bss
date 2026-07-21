-- Same tenant wall as ai_audit: a tenant sees and sets only its own
-- budget. (ai_audit already has broad grants + RLS from V2; the new
-- columns ride its existing policy.)
GRANT SELECT, INSERT, UPDATE, DELETE ON ai_budget TO intelligence_app;

ALTER TABLE ai_budget ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON ai_budget
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));
