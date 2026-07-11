DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'porting_app') THEN
        CREATE ROLE porting_app LOGIN PASSWORD 'porting_app';
    END IF;
END $$;

GRANT USAGE ON SCHEMA public TO porting_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO porting_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO porting_app;

ALTER TABLE porting_order ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON porting_order
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));
