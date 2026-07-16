-- THE MATCH KEY COMING HOME: every bill carries the payment reference
-- (the KID slot — the digits its e-invoice and giro carry). Remittance
-- ingestion matches the bank's credit entries back to bills on it.
ALTER TABLE customer_bill ADD COLUMN payment_reference VARCHAR(32);
CREATE INDEX idx_bill_payment_ref ON customer_bill (tenant_id, payment_reference);
