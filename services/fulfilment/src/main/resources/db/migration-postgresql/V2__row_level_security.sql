-- The double lock, fulfilment edition.
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'fulfilment_app') THEN
        CREATE ROLE fulfilment_app LOGIN PASSWORD 'fulfilment_app';
    END IF;
END
$$;
GRANT USAGE ON SCHEMA public TO fulfilment_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO fulfilment_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO fulfilment_app;

ALTER TABLE shipping_order ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON shipping_order
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));
ALTER TABLE work_order ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON work_order
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));
