-- Same tenant wall as the message table.
GRANT SELECT, INSERT, UPDATE, DELETE ON suppression TO communication_app;

ALTER TABLE suppression ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON suppression
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));
