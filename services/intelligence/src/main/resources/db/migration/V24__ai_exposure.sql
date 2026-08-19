-- Tvilling T-P3: the exposure receipt — every AI call names WHAT CLASS of
-- data left (none | twin | aggregate | raw-redacted | raw) and to which
-- jurisdiction. Compliance becomes a live readout over the same ledger the
-- governor already writes, not an annual PDF.
ALTER TABLE ai_audit ADD COLUMN exposure VARCHAR(16);
ALTER TABLE ai_audit ADD COLUMN jurisdiction VARCHAR(32);
