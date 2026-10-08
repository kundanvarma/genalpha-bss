-- RLS for psp_redirect_session, the same policy as payment and
-- payment_provider_config. Runtime role grants come from V3's default
-- privileges, so a new table is covered.
--
-- This matters more than for most tables: the row says which party started a
-- payment session, so it is the thing the ownership check in confirmSession
-- reads. A tenant that could read another's rows could learn session ids.
ALTER TABLE psp_redirect_session ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON psp_redirect_session
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));
