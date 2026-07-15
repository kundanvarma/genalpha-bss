-- The interaction log becomes the OMNICHANNEL record: touchpoints minted
-- from the event stream (martech sends, order notifications) and logged
-- by external systems carry their origin, and the source reference makes
-- event-driven minting idempotent under at-least-once delivery.
ALTER TABLE party_interaction ADD COLUMN source_ref VARCHAR(64);
ALTER TABLE party_interaction ADD COLUMN source_system VARCHAR(64);
ALTER TABLE party_interaction ADD CONSTRAINT uq_interaction_source UNIQUE (tenant_id, source_ref);
