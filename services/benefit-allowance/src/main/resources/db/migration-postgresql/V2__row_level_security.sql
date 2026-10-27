-- Row-Level Security, the second lock: the runtime role below cannot read or
-- write another tenant's pool even if application code loses a tenant
-- predicate. Same shape as every component: rows compare against the
-- app.tenant_id session variable set per connection checkout; '__system__' is
-- the explicit escape hatch for tenant-spanning jobs. Flyway runs as the
-- owning role (RLS does not bind owners). Postgres-only: H2 runs skip it.
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'benefit_allowance_app') THEN
        CREATE ROLE benefit_allowance_app LOGIN PASSWORD 'benefit_allowance_app';
    END IF;
END
$$;
GRANT USAGE ON SCHEMA public TO benefit_allowance_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO benefit_allowance_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO benefit_allowance_app;

ALTER TABLE choice_pool ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON choice_pool
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));

ALTER TABLE choice_grant ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON choice_grant
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));

ALTER TABLE choice_allocation ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON choice_allocation
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));
