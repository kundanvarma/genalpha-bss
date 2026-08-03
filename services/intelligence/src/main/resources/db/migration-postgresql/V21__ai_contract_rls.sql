-- RLS for ai_contract, same policy as every tenant-scoped table here.
ALTER TABLE ai_contract ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON ai_contract
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));
