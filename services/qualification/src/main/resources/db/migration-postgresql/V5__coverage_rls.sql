-- RLS for the TMF645 tables, same policy as serviceable_area: the runtime
-- role cannot cross tenants even if application code loses a predicate.
ALTER TABLE coverage_map ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON coverage_map
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));

ALTER TABLE service_qualification ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON service_qualification
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));
