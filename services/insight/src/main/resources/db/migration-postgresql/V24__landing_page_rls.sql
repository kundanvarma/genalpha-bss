-- Row-Level Security for landing pages — same tenant lock as the rest.
ALTER TABLE landing_page ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_landing_page ON landing_page
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));
