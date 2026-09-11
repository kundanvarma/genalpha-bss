DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'device_entitlement_app') THEN
        CREATE ROLE device_entitlement_app LOGIN PASSWORD 'device_entitlement_app';
    END IF;
END $$;

GRANT USAGE ON SCHEMA public TO device_entitlement_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO device_entitlement_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO device_entitlement_app;

ALTER TABLE entitlement_subscriber ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON entitlement_subscriber
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));

ALTER TABLE entitlement_device ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON entitlement_device
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));

ALTER TABLE entitlement_token ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON entitlement_token
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));

ALTER TABLE companion_device ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON companion_device
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));

ALTER TABLE subscription_transfer ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON subscription_transfer
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));

ALTER TABLE ecs_request ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON ecs_request
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));
