-- #422: a tenant's fallback provider answers when the first one is down, and
-- the row says so (the provider it stood in for); token counts come from the
-- provider's own usage block when it reports one, and the row says which.
-- Columns on an existing table: its row-level security policy already covers them.
ALTER TABLE ai_audit ADD COLUMN fallback_from VARCHAR(32);
ALTER TABLE ai_audit ADD COLUMN token_source VARCHAR(16);
