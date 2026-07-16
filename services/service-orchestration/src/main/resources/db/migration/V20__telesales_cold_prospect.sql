-- COLD PROSPECTS: an offer may be made to someone who is not a customer
-- yet. The offer carries their contact; the identity is born the honest
-- way — the prospect REGISTERS (that is the identity proof), signs in,
-- and confirms with the code the partner's own SMS delivered.
ALTER TABLE telesales_offer ALTER COLUMN customer_id DROP NOT NULL;
ALTER TABLE telesales_offer ADD COLUMN prospect_email VARCHAR(255);
ALTER TABLE telesales_offer ADD COLUMN prospect_name VARCHAR(255);
