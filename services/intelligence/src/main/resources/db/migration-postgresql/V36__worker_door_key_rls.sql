-- Same tenant wall as ai_budget: a tenant sees and revokes only its own
-- worker keys. The door resolves a presented key as the SYSTEM tenant (it
-- cannot know the tenant before it has read the key), then acts as the key's
-- tenant for everything after.
GRANT SELECT, INSERT, UPDATE, DELETE ON worker_door_key TO intelligence_app;

ALTER TABLE worker_door_key ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON worker_door_key
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));
