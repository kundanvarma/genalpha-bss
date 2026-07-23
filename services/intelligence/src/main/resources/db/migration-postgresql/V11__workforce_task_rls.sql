-- The workforce ledger is tenant data like any other row: RLS keeps one
-- operator's digital workers invisible to another even if application code
-- loses a predicate.
GRANT SELECT, INSERT, UPDATE, DELETE ON workforce_task TO intelligence_app;

ALTER TABLE workforce_task ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON workforce_task
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));
