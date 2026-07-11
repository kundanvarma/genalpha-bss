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
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'paymentmethod_app') THEN
        CREATE ROLE paymentmethod_app LOGIN PASSWORD 'paymentmethod_app';
    END IF;
END
$$;
GRANT USAGE ON SCHEMA public TO paymentmethod_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO paymentmethod_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO paymentmethod_app;

ALTER TABLE payment_method ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON payment_method
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));
