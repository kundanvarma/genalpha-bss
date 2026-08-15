-- Row-Level Security for materialized snapshots — same tenant lock as the rest.
ALTER TABLE audience_snapshot ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_audience_snapshot ON audience_snapshot
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));
