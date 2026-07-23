-- The same second lock the cart table wears: an ACP checkout session is
-- tenant data like any other row — RLS keeps one operator's agent traffic
-- invisible to another even if application code loses a predicate.
GRANT SELECT, INSERT, UPDATE, DELETE ON acp_session TO cart_app;

ALTER TABLE acp_session ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON acp_session
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));
