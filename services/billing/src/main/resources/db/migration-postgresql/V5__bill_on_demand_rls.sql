-- RLS for customer_bill_on_demand (Postgres-only; H2 test skips this location).
-- billing_app already has broad grants from V3.
ALTER TABLE customer_bill_on_demand ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON customer_bill_on_demand
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));
