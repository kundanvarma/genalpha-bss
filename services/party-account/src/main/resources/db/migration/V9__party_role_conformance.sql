-- TMF669 CTK conformance: a PartyRole can be created without an engaged party
-- (the spec makes engagedParty optional), and carries a roleType. Relax the
-- party_id NOT NULL constraint and store the posted roleType so it round-trips.
ALTER TABLE party_role ALTER COLUMN party_id DROP NOT NULL;
ALTER TABLE party_role ADD COLUMN role_type VARCHAR(2000);
