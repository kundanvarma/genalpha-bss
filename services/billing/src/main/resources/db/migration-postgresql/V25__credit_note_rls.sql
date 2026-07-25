GRANT SELECT, INSERT, UPDATE, DELETE ON credit_note, document_sequence TO billing_app;
ALTER TABLE credit_note ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON credit_note
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));
ALTER TABLE document_sequence ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON document_sequence
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));
