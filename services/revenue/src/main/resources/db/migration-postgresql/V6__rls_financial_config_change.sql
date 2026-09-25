-- financial_config_change joins the tenant wall. A new table is unprotected by
-- default, and this one carries who proposed what to a tenant's books.
GRANT SELECT, INSERT, UPDATE, DELETE ON financial_config_change TO revenue_app;
ALTER TABLE financial_config_change ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON financial_config_change
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));
