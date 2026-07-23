-- Agentic Commerce Protocol checkout sessions: the protocol dressing an AI
-- shopping agent drives, riding the same TMF663 cart underneath (cart_id).
-- The idempotency key makes complete replay-safe: the same key returns the
-- same order, never a second one.
CREATE TABLE acp_session (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    cart_id VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    currency VARCHAR(8),
    line_item_json VARCHAR(8000),
    buyer_json VARCHAR(2000),
    completed_order_id VARCHAR(64),
    completed_payment_id VARCHAR(64),
    idempotency_key VARCHAR(255),
    created_at TIMESTAMP WITH TIME ZONE,
    last_update TIMESTAMP WITH TIME ZONE
);
CREATE INDEX idx_acp_session_tenant ON acp_session (tenant_id);
