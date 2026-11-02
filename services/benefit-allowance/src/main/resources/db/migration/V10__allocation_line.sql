-- Benefit Choices (#420): a per-line benefit is picked once per LINE. The
-- allocation records the line it was picked for (the service id of the
-- household's mobile line); null for a benefit picked once per household.
ALTER TABLE choice_allocation ADD COLUMN line_id VARCHAR(64);
