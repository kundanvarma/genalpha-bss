-- Row-level security for the journey tables (second lock, as everywhere).
ALTER TABLE journey ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_journey ON journey
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));
ALTER TABLE journey_enrollment ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_enrollment ON journey_enrollment
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));
