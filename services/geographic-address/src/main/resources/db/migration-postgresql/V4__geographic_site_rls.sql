-- RLS for geographic_site, same policy as geographic_address.
ALTER TABLE geographic_site ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON geographic_site
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));
