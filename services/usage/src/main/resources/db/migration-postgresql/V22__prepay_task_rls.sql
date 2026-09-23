-- prepay_task: the second lock was never switched on for this table. Application code
-- still scopes every query by tenant, so nothing leaked; this is the layer
-- that is meant to hold when a query loses its predicate, and on this table
-- it was not holding. Same policy as every other tenant table in this schema.
ALTER TABLE prepay_task ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON prepay_task
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));
