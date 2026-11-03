-- A decline is never final without a way to a person (#422 review, finding 4).
-- The credit decision is stored in its own transaction BEFORE the operator's
-- policy rules run, so when a rule refuses the order the order rolls back and
-- the decision is left pointing at an order that never existed. These columns
-- say what happened instead: the operator's rule refused it, when, and with the
-- rule's own customer-facing words — so the decision stays explainable and the
-- customer can still ask a person, by the decision's id.
-- No new table: the tenant wall of V11 already covers these columns.
ALTER TABLE credit_decision ADD COLUMN order_refused_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE credit_decision ADD COLUMN order_refusal VARCHAR(400);
