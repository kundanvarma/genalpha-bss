-- FORMAT PROFILES AS DATA: what a country's e-invoice profile IS —
-- the syntax (UBL or CII), the CustomizationID/ProfileID it declares,
-- and whether a payment reference is required — lives in rows, not
-- constants. Adding a country is an INSERT, not a deploy. The tenant's
-- BILL_DISTRIBUTION_FORMAT picks a row by code.
CREATE TABLE bill_format_profile (
    id                VARCHAR(36) PRIMARY KEY,
    tenant_id         VARCHAR(64) NOT NULL,
    code              VARCHAR(64) NOT NULL,
    name              VARCHAR(120) NOT NULL,
    syntax            VARCHAR(8) NOT NULL,
    customization_id  VARCHAR(255),
    profile_id        VARCHAR(255),
    payment_reference BOOLEAN NOT NULL,
    last_update       TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE UNIQUE INDEX idx_format_profile_code ON bill_format_profile (tenant_id, code);

-- Seed the profiles every tenant starts with. EHF is Peppol BIS 3.0
-- plus Norway's NO-R rules (the KID payment reference); A-NZ is the
-- same UBL skeleton wearing Australia/New Zealand's customization —
-- exactly the point: a country profile is a row.
INSERT INTO bill_format_profile VALUES
  ('ehf-genalpha', 'genalpha', 'ehf', 'EHF Billing 3.0 (Norway)', 'ubl',
   'urn:cen.eu:en16931:2017#compliant#urn:fdc:peppol.eu:2017:poacc:billing:3.0',
   'urn:fdc:peppol.eu:2017:poacc:billing:01:1.0', TRUE, CURRENT_TIMESTAMP),
  ('peppol-genalpha', 'genalpha', 'peppol', 'Peppol BIS Billing 3.0', 'ubl',
   'urn:cen.eu:en16931:2017#compliant#urn:fdc:peppol.eu:2017:poacc:billing:3.0',
   'urn:fdc:peppol.eu:2017:poacc:billing:01:1.0', FALSE, CURRENT_TIMESTAMP),
  ('cii-genalpha', 'genalpha', 'cii', 'EN 16931 UN/CEFACT CII', 'cii',
   'urn:cen.eu:en16931:2017', NULL, FALSE, CURRENT_TIMESTAMP),
  ('aunz-genalpha', 'genalpha', 'aunz', 'A-NZ Peppol BIS 3.0', 'ubl',
   'urn:cen.eu:en16931:2017#conformant#urn:fdc:peppol.eu:2017:poacc:billing:international:aunz:3.0',
   'urn:fdc:peppol.eu:2017:poacc:billing:01:1.0', FALSE, CURRENT_TIMESTAMP),
  ('ehf-nova', 'nova', 'ehf', 'EHF Billing 3.0 (Norway)', 'ubl',
   'urn:cen.eu:en16931:2017#compliant#urn:fdc:peppol.eu:2017:poacc:billing:3.0',
   'urn:fdc:peppol.eu:2017:poacc:billing:01:1.0', TRUE, CURRENT_TIMESTAMP),
  ('peppol-nova', 'nova', 'peppol', 'Peppol BIS Billing 3.0', 'ubl',
   'urn:cen.eu:en16931:2017#compliant#urn:fdc:peppol.eu:2017:poacc:billing:3.0',
   'urn:fdc:peppol.eu:2017:poacc:billing:01:1.0', FALSE, CURRENT_TIMESTAMP),
  ('cii-nova', 'nova', 'cii', 'EN 16931 UN/CEFACT CII', 'cii',
   'urn:cen.eu:en16931:2017', NULL, FALSE, CURRENT_TIMESTAMP),
  ('aunz-nova', 'nova', 'aunz', 'A-NZ Peppol BIS 3.0', 'ubl',
   'urn:cen.eu:en16931:2017#conformant#urn:fdc:peppol.eu:2017:poacc:billing:international:aunz:3.0',
   'urn:fdc:peppol.eu:2017:poacc:billing:01:1.0', FALSE, CURRENT_TIMESTAMP);
