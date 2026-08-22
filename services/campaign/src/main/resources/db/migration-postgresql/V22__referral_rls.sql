-- Same tenant wall as every campaign table.
GRANT SELECT, INSERT, UPDATE, DELETE ON referral_code TO campaign_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON referral_conversion TO campaign_app;

ALTER TABLE referral_code ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON referral_code
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));

ALTER TABLE referral_conversion ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON referral_conversion
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));
