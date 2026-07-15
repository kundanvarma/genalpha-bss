-- Same tenant wall as the campaign tables.
GRANT SELECT, INSERT, UPDATE, DELETE ON martech_setting, marketing_touch TO campaign_app;

ALTER TABLE martech_setting ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON martech_setting
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));

ALTER TABLE marketing_touch ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON marketing_touch
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));
