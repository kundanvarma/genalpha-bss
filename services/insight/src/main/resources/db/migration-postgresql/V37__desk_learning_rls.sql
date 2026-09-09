-- RLS for desk learning, same policy as every insight table.
ALTER TABLE desk_event ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON desk_event
    USING (current_setting('app.tenant_id', true) = '__system__' OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__' OR tenant_id = current_setting('app.tenant_id', true));
ALTER TABLE desk_preset ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON desk_preset
    USING (current_setting('app.tenant_id', true) = '__system__' OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__' OR tenant_id = current_setting('app.tenant_id', true));
ALTER TABLE desk_decision ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON desk_decision
    USING (current_setting('app.tenant_id', true) = '__system__' OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__' OR tenant_id = current_setting('app.tenant_id', true));
