-- RLS for the VoC alert ledger, same policy as every insight table.
ALTER TABLE voc_alert ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON voc_alert
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));
