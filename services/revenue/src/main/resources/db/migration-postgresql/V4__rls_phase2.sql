-- period_close joins the tenant wall.
GRANT SELECT, INSERT, UPDATE, DELETE ON period_close TO revenue_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON tick_lock TO revenue_app;
ALTER TABLE period_close ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON period_close
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));
