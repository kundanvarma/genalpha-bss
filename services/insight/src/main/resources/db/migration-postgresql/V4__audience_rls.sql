-- Row-Level Security for saved audiences — the same tenant lock as the rest.
-- (insight_app already auto-inherits table grants via ALTER DEFAULT PRIVILEGES.)
ALTER TABLE audience ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_audience ON audience
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));
