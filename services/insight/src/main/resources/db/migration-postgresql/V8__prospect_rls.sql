-- Row-Level Security for prospects — same tenant lock as the rest.
ALTER TABLE prospect ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_prospect ON prospect
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));
