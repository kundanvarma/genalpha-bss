-- Policy & eligibility rules: business rules authored as DATA (not code), so
-- launching a new product rule is a row, not a redeploy. Each rule is a
-- JSON-logic condition evaluated at a decision point (domain, e.g. 'order');
-- when a DENY rule's condition matches the request context, the action is
-- refused with the rule's message. Rules are tenant-owned (RLS in V2).
CREATE TABLE policy_rule (
    id           VARCHAR(36) PRIMARY KEY,
    href         VARCHAR(255),
    tenant_id    VARCHAR(64) NOT NULL DEFAULT 'genalpha',
    name         VARCHAR(255) NOT NULL,
    description  VARCHAR(2000),
    -- decision point this rule applies at: 'order' (evaluated when an order is placed)
    domain       VARCHAR(64) NOT NULL DEFAULT 'order',
    -- 'deny' blocks the action when the condition matches (allow reserved for future)
    effect       VARCHAR(16) NOT NULL DEFAULT 'deny',
    -- lower runs first; first matching DENY wins
    priority     INT NOT NULL DEFAULT 100,
    enabled      BOOLEAN NOT NULL DEFAULT TRUE,
    -- JSON-logic expression over the request context; matches => effect applies
    condition    VARCHAR(4000) NOT NULL,
    -- shown to the customer/channel when this rule denies
    message      VARCHAR(1000),
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    last_update  TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_policy_rule_tenant ON policy_rule (tenant_id);
CREATE INDEX idx_policy_rule_domain ON policy_rule (tenant_id, domain, enabled);

CREATE TABLE event_outbox (
    id         VARCHAR(36) PRIMARY KEY,
    event_type VARCHAR(128) NOT NULL,
    payload    VARCHAR(8000) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

-- A disabled example rule per demo tenant, so the console shows the shape and
-- an operator can enable/clone it. Disabled => no effect on ordering until
-- switched on (which needs no redeploy — that is the whole point).
INSERT INTO policy_rule (id, href, tenant_id, name, description, domain, effect, priority, enabled, condition, message, created_at, last_update)
VALUES (
    'example-quantity-cap', '/tmf-api/policyManagement/v4/policyRule/example-quantity-cap',
    'genalpha', 'Example: max 5 of any one offering per order',
    'Denies an order asking for more than five units of a single offering. Disabled by default — enable to activate, no redeploy.',
    'order', 'deny', 100, FALSE,
    '{">":[{"var":"maxLineQuantity"},5]}',
    'You can order at most 5 of a single item. Please split into separate orders.',
    now(), now());
