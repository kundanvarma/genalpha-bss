-- A bill says when it is due. Until now lateness was recomputed wherever it
-- was needed (collections added the dunning policy's payment term to the bill
-- date every sweep), so no screen could show a due date and no two readers
-- were guaranteed to agree. The date is now a fact on the bill, stamped at
-- bill run from the tenant's active dunning policy, and the term that
-- produced it is recorded beside it so a later policy change cannot silently
-- rewrite history.
ALTER TABLE customer_bill ADD COLUMN due_date TIMESTAMP WITH TIME ZONE;
ALTER TABLE customer_bill ADD COLUMN payment_term_days INT;

-- Existing bills: the house default term (14 days, the same default the
-- dunning policy entity carries). A tenant whose policy differs gets the real
-- term on its next bill; backfilling per tenant would need a correlated
-- interval multiplication that H2 and Postgres spell differently, and this
-- column is a statement about the past, not a rule.
UPDATE customer_bill SET due_date = bill_date + INTERVAL '14' DAY, payment_term_days = 14
 WHERE due_date IS NULL AND bill_date IS NOT NULL;

CREATE INDEX idx_customer_bill_due_date ON customer_bill (tenant_id, due_date);
