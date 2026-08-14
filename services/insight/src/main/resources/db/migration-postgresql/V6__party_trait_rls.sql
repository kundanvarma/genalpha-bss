-- Row-Level Security for the BSS-native trait store — same tenant lock as the
-- rest. (insight_app auto-inherits table grants via ALTER DEFAULT PRIVILEGES.)
ALTER TABLE party_trait ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_party_trait ON party_trait
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));
