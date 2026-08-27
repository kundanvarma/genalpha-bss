DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'migration_app') THEN
        CREATE ROLE migration_app LOGIN PASSWORD 'migration_app';
    END IF;
END $$;

GRANT USAGE ON SCHEMA public TO migration_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO migration_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO migration_app;

ALTER TABLE migration_plan ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON migration_plan
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));

ALTER TABLE migration_customer ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON migration_customer
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));
