-- Same tenant wall as every intelligence table: a tenant simulates only
-- its own money.
GRANT SELECT, INSERT, UPDATE, DELETE ON price_sim_report TO intelligence_app;

ALTER TABLE price_sim_report ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON price_sim_report
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));
