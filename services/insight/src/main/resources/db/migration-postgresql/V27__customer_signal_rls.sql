-- RLS for the signal store, same policy as every insight table.
ALTER TABLE customer_signal ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON customer_signal
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));
