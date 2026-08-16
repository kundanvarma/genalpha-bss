-- Same tenant wall as the rest of the sales tables.
GRANT SELECT, INSERT, UPDATE, DELETE ON opportunity_item, opportunity_activity TO quote_app;

ALTER TABLE opportunity_item ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON opportunity_item
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));

ALTER TABLE opportunity_activity ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON opportunity_activity
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));
