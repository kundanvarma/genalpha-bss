-- RLS for the new spec-facing reserve_product_stock resource (Postgres-only;
-- H2 test runs skip this vendor location). Grants were already made broadly to
-- stock_app in V3 (ALL TABLES + default privileges), so only the policy here.
ALTER TABLE reserve_product_stock ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON reserve_product_stock
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));
