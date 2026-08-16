GRANT SELECT, INSERT, UPDATE, DELETE ON lead_scoring_rule, lead_routing_rule TO quote_app;

ALTER TABLE lead_scoring_rule ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON lead_scoring_rule
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));

ALTER TABLE lead_routing_rule ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON lead_routing_rule
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));
