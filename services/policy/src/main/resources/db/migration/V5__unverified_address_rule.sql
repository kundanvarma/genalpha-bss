-- freg F-P3: the address-verification gate, shipped as DATA and DISABLED —
-- exactly like example-quantity-cap. An operator who wants Norwegian-market
-- posture enables it (no redeploy): a home delivery whose address is
-- hand-typed rather than registry-verified is refused with a message that
-- names the alternatives. Orders with nothing shipping have neither var, so
-- the rule does not fire on them; guests type addresses too, so an operator
-- enabling this is choosing registered-address-or-pickup for EVERYONE.
INSERT INTO policy_rule (id, href, tenant_id, name, description, domain, effect, priority, enabled, condition, message, created_at, last_update)
VALUES (
    'example-unverified-address', '/tmf-api/policyManagement/v4/policyRule/example-unverified-address',
    'genalpha', 'Example: home delivery needs a registry-verified address',
    'Denies a home-delivery order whose address is hand-typed (addressSource=manual) rather than verified against the national registry. Disabled by default — enable to activate, no redeploy.',
    'order', 'deny', 90, FALSE,
    '{"and":[{"==":[{"var":"deliveryMethod"},"home"]},{"==":[{"var":"addressSource"},"manual"]}]}',
    'For home delivery we ship to your registered address — use it at checkout, or choose a pickup point (photo ID at handover).',
    now(), now());
