-- The double lock, loyalty edition.
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'loyalty_app') THEN
        CREATE ROLE loyalty_app LOGIN PASSWORD 'loyalty_app';
    END IF;
END
$$;
GRANT USAGE ON SCHEMA public TO loyalty_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO loyalty_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO loyalty_app;

ALTER TABLE loyalty_program ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON loyalty_program
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));
ALTER TABLE loyalty_member ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON loyalty_member
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));
ALTER TABLE loyalty_transaction ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON loyalty_transaction
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));
