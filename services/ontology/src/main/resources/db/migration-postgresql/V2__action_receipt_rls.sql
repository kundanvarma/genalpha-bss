-- Row-Level Security for the receipt store, the same two-lock shape every
-- tenant table in the fleet carries (ADR 0003): Flyway keeps running as the
-- owning role, because RLS does not bind table owners, and only the runtime
-- datasource switches to the restricted role below. Postgres-only migration;
-- H2 test runs skip this version via the vendor location.
--
-- This is the table where leakage would be worst: it holds who asked for what,
-- under which authority, with the inputs they sent.
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'ontology_app') THEN
        CREATE ROLE ontology_app LOGIN PASSWORD 'ontology_app';
    END IF;
END
$$;
GRANT USAGE ON SCHEMA public TO ontology_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO ontology_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO ontology_app;

ALTER TABLE action_receipt ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON action_receipt
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));
