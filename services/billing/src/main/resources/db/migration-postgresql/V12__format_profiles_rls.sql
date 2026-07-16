-- Same tenant wall as every billing table: one tenant's profile edits
-- never touch another's.
GRANT SELECT, INSERT, UPDATE, DELETE ON bill_format_profile TO billing_app;

ALTER TABLE bill_format_profile ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON bill_format_profile
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));
