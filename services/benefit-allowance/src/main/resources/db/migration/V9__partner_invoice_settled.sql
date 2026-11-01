-- Partner invoices, review of #390: when an invoice is paid, the part of the
-- month's accrual it settles is kept beside it, so a partner's second invoice
-- for the same month settles only what is left, and revenue books anything
-- invoiced above the accrual as a variance rather than leaving the payable short.
ALTER TABLE partner_invoice ADD COLUMN settled_accrual NUMERIC(12, 2);
