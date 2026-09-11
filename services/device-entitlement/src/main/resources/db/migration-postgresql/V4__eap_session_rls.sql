GRANT SELECT, INSERT, UPDATE, DELETE ON eap_session TO device_entitlement_app;
ALTER TABLE eap_session ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON eap_session
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));
