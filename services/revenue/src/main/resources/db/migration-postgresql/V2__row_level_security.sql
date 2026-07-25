-- The double lock, revenue edition.
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'revenue_app') THEN
        CREATE ROLE revenue_app LOGIN PASSWORD 'revenue_app';
    END IF;
END
$$;
GRANT USAGE ON SCHEMA public TO revenue_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO revenue_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO revenue_app;

ALTER TABLE journal_entry ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON journal_entry
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));
ALTER TABLE journal_line ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON journal_line
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));
ALTER TABLE account_mapping ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON account_mapping
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));
