-- Row-Level Security, the second lock (see any sibling service).
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'insight_app') THEN
        CREATE ROLE insight_app LOGIN PASSWORD 'insight_app';
    END IF;
END
$$;
GRANT USAGE ON SCHEMA public TO insight_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO insight_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO insight_app;

ALTER TABLE visitor_profile ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON visitor_profile
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));
ALTER TABLE visitor_event ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_events ON visitor_event
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));
