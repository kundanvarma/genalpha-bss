-- Redirect/BNPL payments (PSP-P2). A payment created from a redirect session
-- records the session id so confirm (return leg AND webhook) is idempotent — a
-- customer who returns and a webhook that fires never double-book. The webhook is
-- HMAC-verified with a per-tenant secret named (like the API key) by a secret-ref.
ALTER TABLE payment ADD COLUMN session_ref VARCHAR(128);
ALTER TABLE payment_provider_config ADD COLUMN webhook_secret_ref VARCHAR(128);
