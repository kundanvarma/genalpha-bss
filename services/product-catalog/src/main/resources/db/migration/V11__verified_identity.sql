-- Postpaid/regulated offerings can require a verified real-world identity
-- (BankID/Vipps) at checkout. The flag lives on the offering; ordering
-- enforces it against the buyer's authentication assurance level.
ALTER TABLE product_offering ADD COLUMN IF NOT EXISTS requires_verified_identity BOOLEAN;
