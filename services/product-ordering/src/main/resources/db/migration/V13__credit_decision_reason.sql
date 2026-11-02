-- Credit explanations (#422, GDPR Art. 22 / CJEU C-203/22, CCD2 Art. 18):
-- every decision now keeps WHY in two forms — a short machine code and the
-- plain-language sentence the customer is shown — derived only from what the
-- service actually received (decision, score band, remarks present). Still no
-- report: the reason is category-level, never the bureau's own data.
--
-- order_id ties a decision to the order it was taken for, so a customer can
-- ask about THEIR order; review_* records that they asked a person to look at
-- it (one request per decision — the ticket id makes a second click a replay).
-- No new table: the tenant wall of V11 already covers these columns.
ALTER TABLE credit_decision ADD COLUMN order_id VARCHAR(36);
ALTER TABLE credit_decision ADD COLUMN reason_code VARCHAR(32);
ALTER TABLE credit_decision ADD COLUMN reason VARCHAR(400);
ALTER TABLE credit_decision ADD COLUMN review_requested_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE credit_decision ADD COLUMN review_ticket_id VARCHAR(64);

CREATE INDEX idx_credit_decision_order ON credit_decision (tenant_id, order_id);
