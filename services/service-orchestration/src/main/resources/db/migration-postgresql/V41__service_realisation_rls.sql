-- service_realisation: the second lock, same policy as every other tenant table
-- in this schema (rls_check.py judges the live catalogue on every pull request).
ALTER TABLE service_realisation ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON service_realisation
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));
