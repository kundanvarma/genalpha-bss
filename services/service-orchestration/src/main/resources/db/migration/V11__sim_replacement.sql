-- SIM replacement: the number lives on the SERVICE, the card is
-- expendable. A lost/stolen card is BLOCKED at the platform and a fresh
-- one is minted against the same service; the old row keeps its story.
ALTER TABLE sim_card ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'active';
ALTER TABLE sim_card ADD COLUMN replaced_reason VARCHAR(32);
