-- Row-Level Security for the marketing opt-out — same tenant lock as the rest.
ALTER TABLE marketing_opt_out ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_marketing_opt_out ON marketing_opt_out
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));
