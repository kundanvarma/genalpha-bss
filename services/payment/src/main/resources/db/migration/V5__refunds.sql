-- Refunds: money can go BACK, partially or fully. The payment keeps a
-- running refunded total; the record never claims money moved when it
-- did not (the PSP must confirm every movement).
ALTER TABLE payment ADD COLUMN refunded_amount NUMERIC(12,2) NOT NULL DEFAULT 0;
