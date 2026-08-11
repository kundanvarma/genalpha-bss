-- RLS for payment_provider_config, same policy as payment. Runtime role grants
-- come from V3's default privileges, so a new table is covered.
ALTER TABLE payment_provider_config ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON payment_provider_config
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));
