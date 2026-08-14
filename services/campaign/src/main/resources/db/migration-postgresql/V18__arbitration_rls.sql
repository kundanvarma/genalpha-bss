-- Row-level security for the NBA arbitration log (second lock, as everywhere).
ALTER TABLE arbitration_decision ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_arbitration ON arbitration_decision
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));
