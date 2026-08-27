-- Same tenant wall as every party table (Postgres-only pair of V22).
GRANT SELECT, INSERT, UPDATE, DELETE ON registry_feed_cursor TO party_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON directory_setting TO party_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON directory_export_run TO party_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON directory_export_row TO party_app;

ALTER TABLE registry_feed_cursor ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON registry_feed_cursor
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));

ALTER TABLE directory_setting ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON directory_setting
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));

ALTER TABLE directory_export_run ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON directory_export_run
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));

ALTER TABLE directory_export_row ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON directory_export_row
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));
