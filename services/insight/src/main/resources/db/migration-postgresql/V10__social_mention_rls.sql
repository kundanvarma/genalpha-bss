-- Row-Level Security for brand mentions — same tenant lock as the rest.
ALTER TABLE social_mention ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_social_mention ON social_mention
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));
