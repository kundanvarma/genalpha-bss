-- RLS for the mobile-wholesale provider tables (usage_app). Postgres-only.
GRANT SELECT, INSERT, UPDATE, DELETE ON provider_rate_card TO usage_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON provider_usage_ledger TO usage_app;

ALTER TABLE provider_rate_card ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON provider_rate_card
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));

ALTER TABLE provider_usage_ledger ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON provider_usage_ledger
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));
