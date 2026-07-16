-- How this customer wants their bill delivered: 'paper' (a print job to
-- the distribution partner), 'einvoice' (structured XML to the partner's
-- access point), or 'digital' (in-app/email only — no partner delivery).
-- Null = the tenant's default channel, the historical behavior.
ALTER TABLE individual ADD COLUMN bill_delivery VARCHAR(16);
