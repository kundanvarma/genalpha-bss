-- The double lock, hub edition.
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'eventhub_app') THEN
        CREATE ROLE eventhub_app LOGIN PASSWORD 'eventhub_app';
    END IF;
END
$$;
GRANT USAGE ON SCHEMA public TO eventhub_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO eventhub_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO eventhub_app;

ALTER TABLE hub_subscription ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON hub_subscription
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));
ALTER TABLE hub_delivery ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON hub_delivery
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));
