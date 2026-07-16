-- The last two named formats, as rows like every other:
--  * UN/EDIFACT INVOIC — the pre-XML B2B lingua franca a legacy trading
--    partner may still demand; segments, not tags.
--  * Factur-X / ZUGFeRD — the French/German hybrid: a human-readable
--    PDF with the machine-readable EN 16931 CII embedded inside it.
INSERT INTO bill_format_profile VALUES
  ('edifact-genalpha', 'genalpha', 'edifact', 'UN/EDIFACT INVOIC D96A', 'edifact',
   NULL, NULL, FALSE, CURRENT_TIMESTAMP),
  ('facturx-genalpha', 'genalpha', 'facturx', 'Factur-X / ZUGFeRD (CII in PDF)', 'facturx',
   'urn:factur-x.eu:1p0:en16931', NULL, FALSE, CURRENT_TIMESTAMP),
  ('edifact-nova', 'nova', 'edifact', 'UN/EDIFACT INVOIC D96A', 'edifact',
   NULL, NULL, FALSE, CURRENT_TIMESTAMP),
  ('facturx-nova', 'nova', 'facturx', 'Factur-X / ZUGFeRD (CII in PDF)', 'facturx',
   'urn:factur-x.eu:1p0:en16931', NULL, FALSE, CURRENT_TIMESTAMP);
