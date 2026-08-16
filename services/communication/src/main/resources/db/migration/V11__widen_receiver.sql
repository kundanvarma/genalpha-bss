-- A message to a PROSPECT is keyed by "prospect:<email>", which overflows the
-- UUID-sized column — the prospect-reach path failed for normal emails. Widen
-- it to hold an email-length recipient. (SET DATA TYPE is valid on H2 + Postgres.)
ALTER TABLE communication_message ALTER COLUMN receiver_party_id SET DATA TYPE VARCHAR(320);
