DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'device_commerce_app') THEN
        CREATE ROLE device_commerce_app LOGIN PASSWORD 'device_commerce_app';
    END IF;
END $$;

GRANT USAGE ON SCHEMA public TO device_commerce_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO device_commerce_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO device_commerce_app;

ALTER TABLE device_agreement ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON device_agreement
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));

ALTER TABLE trade_in_valuation ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON trade_in_valuation
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));

ALTER TABLE grading_event ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON grading_event
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));

ALTER TABLE withdrawal_case ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON withdrawal_case
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));

ALTER TABLE trade_in_residual ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON trade_in_residual
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));

ALTER TABLE device_flag ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON device_flag
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));
