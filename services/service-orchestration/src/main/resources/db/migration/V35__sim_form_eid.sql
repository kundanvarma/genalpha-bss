-- A SIM is a card or an eSIM profile: the form and the eUICC it lives on
-- (a TS.43 ODSA transfer lands a profile on the new phone's EID).
ALTER TABLE sim_card ADD COLUMN form VARCHAR(16) NOT NULL DEFAULT 'physical';
ALTER TABLE sim_card ADD COLUMN eid VARCHAR(40);
