-- partner_activation: the second lock, same policy as every other tenant table
-- in this schema (rls_check.py judges the live catalogue on every pull request).
-- this schema grants per table (no default privileges), so the runtime role needs it said
GRANT SELECT, INSERT, UPDATE, DELETE ON partner_activation TO som_app;
ALTER TABLE partner_activation ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON partner_activation
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));
