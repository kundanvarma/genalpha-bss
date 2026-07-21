-- Same tenant wall as every party table.
GRANT SELECT, INSERT, UPDATE, DELETE ON erasure_record TO party_app;

ALTER TABLE erasure_record ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON erasure_record
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));
