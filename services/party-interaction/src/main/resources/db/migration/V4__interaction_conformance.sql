-- TMF683 CTK conformance: the spec makes description and relatedParty optional
-- and carries rich fields (channel[], reason, direction). Relax the NOT NULL
-- constraints and store the posted body so any spec field round-trips on GET.
ALTER TABLE party_interaction ALTER COLUMN description DROP NOT NULL;
ALTER TABLE party_interaction ALTER COLUMN customer_party_id DROP NOT NULL;
ALTER TABLE party_interaction ADD COLUMN payload_json VARCHAR(8000);
