-- Row-Level Security, the second lock: the runtime role below cannot read
-- or write another tenant's rows even if application code loses a tenant
-- predicate. The policies compare each row against the app.tenant_id session
-- variable (set per connection checkout); '__system__' is the explicit
-- escape hatch for tenant-spanning system jobs. Flyway keeps running as the
-- owning role — RLS does not bind table owners — so only the runtime
-- datasource switches to the restricted role. Postgres-only migration:
-- H2 test runs skip this version (vendor location).
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'party_app') THEN
        CREATE ROLE party_app LOGIN PASSWORD 'party_app';
    END IF;
END
$$;
GRANT USAGE ON SCHEMA public TO party_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO party_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO party_app;

ALTER TABLE individual ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON individual
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));

ALTER TABLE organization ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON organization
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));

ALTER TABLE billing_account ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON billing_account
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));

ALTER TABLE bill_format ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON bill_format
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));

ALTER TABLE billing_cycle_specification ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON billing_cycle_specification
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));

ALTER TABLE financial_account ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON financial_account
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));

ALTER TABLE party_account ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON party_account
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));

ALTER TABLE settlement_account ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON settlement_account
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));

ALTER TABLE bill_presentation_media ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON bill_presentation_media
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));
