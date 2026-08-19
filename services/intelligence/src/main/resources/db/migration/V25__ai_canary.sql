-- Tvilling T-P4: the canary — a per-call synthetic marker embedded in every
-- outbound prompt that exposes anything. A later probe asks the provider to
-- complete it: a provider that trained on our traffic can; one that kept its
-- word cannot. Non-retention becomes something the ledger MONITORS, not
-- something a contract asserts once a year.
ALTER TABLE ai_audit ADD COLUMN canary VARCHAR(24);
