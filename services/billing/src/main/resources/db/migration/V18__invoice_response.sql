-- THE BUYER ANSWERS: a Peppol Invoice Response (BIS 3 IMR) is the
-- receiver's business reply to an e-invoice — acknowledged, accepted,
-- rejected, paid — flowing back through the access point. It lands on
-- the delivery ledger row, so one row tells the document's WHOLE story:
-- pending → sent → accepted → paid (or rejected, with the buyer's words).
ALTER TABLE bill_distribution ADD COLUMN buyer_status VARCHAR(32);
ALTER TABLE bill_distribution ADD COLUMN buyer_note VARCHAR(500);
ALTER TABLE bill_distribution ADD COLUMN responded_at TIMESTAMP WITH TIME ZONE;
