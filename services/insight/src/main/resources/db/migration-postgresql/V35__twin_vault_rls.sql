-- RLS for the twin vault, same policy as every insight table.
ALTER TABLE twin_vault ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON twin_vault
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));
