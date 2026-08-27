GRANT SELECT, INSERT, UPDATE, DELETE ON party_billing_channel TO billing_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON direct_debit_mandate TO billing_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON direct_debit_claim TO billing_app;

ALTER TABLE party_billing_channel ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON party_billing_channel
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));

ALTER TABLE direct_debit_mandate ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON direct_debit_mandate
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));

ALTER TABLE direct_debit_claim ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON direct_debit_claim
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));
