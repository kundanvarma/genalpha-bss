-- The runtime role is RLS-restricted; Flyway runs as the owner (see yml).
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'intelligence_app') THEN
        CREATE ROLE intelligence_app LOGIN PASSWORD 'intelligence_app';
    END IF;
END $$;

GRANT USAGE ON SCHEMA public TO intelligence_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO intelligence_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO intelligence_app;

ALTER TABLE ai_audit ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON ai_audit
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));
