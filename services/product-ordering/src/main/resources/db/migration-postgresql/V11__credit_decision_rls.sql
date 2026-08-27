-- Same tenant wall as product_order (Postgres-only pair of V10).
GRANT SELECT, INSERT, UPDATE, DELETE ON credit_decision TO ordering_app;

ALTER TABLE credit_decision ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON credit_decision
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));
