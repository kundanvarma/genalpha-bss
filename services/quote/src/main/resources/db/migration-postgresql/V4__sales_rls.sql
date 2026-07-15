-- Same tenant wall as the quote table: rows are only visible to the
-- tenant that owns them (or the migration owner acting as __system__).
GRANT SELECT, INSERT, UPDATE, DELETE ON sales_lead, sales_opportunity TO quote_app;

ALTER TABLE sales_lead ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON sales_lead
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));

ALTER TABLE sales_opportunity ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON sales_opportunity
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));
