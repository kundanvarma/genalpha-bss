-- Redact before send: every prompt leaves the box with personal values
-- replaced by typed placeholders unless the tenant opted into raw exposure.
-- The ledger says which it was, and how many values the redactor replaced —
-- the prompt/response columns stay the redacted copy either way.
ALTER TABLE ai_audit ADD COLUMN raw_exposure BOOLEAN;
ALTER TABLE ai_audit ADD COLUMN redacted_fields INTEGER;
