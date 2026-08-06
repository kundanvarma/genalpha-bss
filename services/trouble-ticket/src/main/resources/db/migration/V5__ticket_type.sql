-- TMF621: a ticket declares its TYPE (support, incident, complaint…) —
-- mandatory in the spec, adopted fleet-wide. Existing rows were raised by
-- the support flows and are labeled as such.
ALTER TABLE trouble_ticket ADD COLUMN ticket_type VARCHAR(64);
UPDATE trouble_ticket SET ticket_type = 'support' WHERE ticket_type IS NULL;
