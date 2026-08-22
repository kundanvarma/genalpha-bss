GRANT SELECT, INSERT, UPDATE, DELETE ON community_goal TO campaign_app;

ALTER TABLE community_goal ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON community_goal
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));
