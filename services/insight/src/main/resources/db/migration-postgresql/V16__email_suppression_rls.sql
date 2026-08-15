-- Row-Level Security for the DNC ledger — same tenant lock as the rest.
ALTER TABLE email_suppression ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_email_suppression ON email_suppression
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));
