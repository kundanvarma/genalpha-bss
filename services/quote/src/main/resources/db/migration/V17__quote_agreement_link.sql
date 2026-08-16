-- On acceptance a quote becomes an order (already) AND a contract: the TMF651
-- agreement it produced, linked back here.
ALTER TABLE quote ADD COLUMN agreement_id VARCHAR(36);
