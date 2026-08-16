GRANT SELECT, INSERT, UPDATE, DELETE ON quote_pricing_rule, pipeline_snapshot TO quote_app;

ALTER TABLE quote_pricing_rule ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON quote_pricing_rule
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));

ALTER TABLE pipeline_snapshot ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON pipeline_snapshot
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));
