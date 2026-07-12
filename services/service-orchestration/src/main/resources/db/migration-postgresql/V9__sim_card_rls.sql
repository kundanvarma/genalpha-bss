-- Same second lock as every other domain table.
ALTER TABLE sim_card ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON sim_card
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));
