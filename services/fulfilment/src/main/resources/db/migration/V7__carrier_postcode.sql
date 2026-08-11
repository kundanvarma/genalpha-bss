-- Rule-based routing (C-P4): a carrier can serve a postcode PREFIX. On a home
-- booking with no explicit carrier, the longest matching prefix wins (e.g.
-- Helthjem for '0' = metro Oslo, Bring everywhere else). Empty/null = no rule.
ALTER TABLE carrier_config ADD COLUMN postcode_prefix VARCHAR(16);
