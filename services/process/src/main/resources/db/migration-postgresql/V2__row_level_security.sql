-- The double lock, process edition.
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'process_app') THEN
        CREATE ROLE process_app LOGIN PASSWORD 'process_app';
    END IF;
END
$$;
GRANT USAGE ON SCHEMA public TO process_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO process_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO process_app;

ALTER TABLE process_flow_spec ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON process_flow_spec
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));
ALTER TABLE process_flow ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON process_flow
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));
ALTER TABLE task_flow ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON task_flow
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));
ALTER TABLE process_event ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON process_event
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));
