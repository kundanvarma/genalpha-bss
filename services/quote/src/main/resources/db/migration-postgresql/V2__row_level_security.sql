-- The runtime role is RLS-restricted; Flyway runs as the owner (see yml).
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'quote_app') THEN
        CREATE ROLE quote_app LOGIN PASSWORD 'quote_app';
    END IF;
END $$;

GRANT USAGE ON SCHEMA public TO quote_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO quote_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO quote_app;

ALTER TABLE quote ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON quote
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));
