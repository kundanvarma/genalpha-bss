-- Row-level security for learning contracts (second lock, as everywhere).
ALTER TABLE learning_contract ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_learning_contract ON learning_contract
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));
