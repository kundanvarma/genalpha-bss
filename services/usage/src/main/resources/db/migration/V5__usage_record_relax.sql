-- TMF635: usageType (usage_spec_name) and relatedParty (owner_party_id) are
-- optional on a usage record per the spec; relax the NOT NULL constraints so a
-- spec-minimal usage (usageSpecification only) can be ingested.
ALTER TABLE usage_record ALTER COLUMN usage_spec_name DROP NOT NULL;
ALTER TABLE usage_record ALTER COLUMN owner_party_id DROP NOT NULL;
