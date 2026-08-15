-- Row-Level Security for the social-care DM store — same tenant lock as the rest.
ALTER TABLE social_dm ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_social_dm ON social_dm
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));
