-- Pricing rules: the same data-authored engine, now producing a price
-- ADJUSTMENT instead of a deny. A rule with domain='pricing' and effect='adjust'
-- carries an adjustment (percent of subtotal, or a fixed amount; sign encodes
-- discount vs surcharge) applied when its JSON-logic condition matches the
-- pricing context (subtotal, offerings, party, verified identity, ...).
-- Evaluated at cart/quote/bill time — add or disable a price rule with a row.
ALTER TABLE policy_rule ADD COLUMN adjustment_type VARCHAR(16);
ALTER TABLE policy_rule ADD COLUMN adjustment_value NUMERIC(12, 4);

-- A disabled example pricing rule: 10% loyalty discount for verified customers.
INSERT INTO policy_rule (id, href, tenant_id, name, description, domain, effect, priority, enabled, condition, message, adjustment_type, adjustment_value, created_at, last_update)
VALUES (
    'example-loyalty-discount', '/tmf-api/policyManagement/v4/policyRule/example-loyalty-discount',
    'genalpha', 'Example: 10% loyalty discount for verified customers',
    'Takes 10% off the recurring subtotal when the customer has a verified identity. Disabled by default — enable to activate, no redeploy.',
    'pricing', 'adjust', 100, FALSE,
    '{"var":"verifiedIdentity"}',
    'Loyalty discount (verified customer)',
    'percent', -10.0000,
    now(), now());
