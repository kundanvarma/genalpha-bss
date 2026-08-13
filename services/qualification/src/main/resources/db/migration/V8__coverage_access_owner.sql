-- Open access: a coverage row may be served by a third-party fibre OWNER rather
-- than our own network. access_owner names the wholesaler (null = our own
-- network / legacy); access_layer is what they sell (L2-VULA | L3-activated).
ALTER TABLE coverage_map ADD COLUMN access_owner VARCHAR(64);
ALTER TABLE coverage_map ADD COLUMN access_layer VARCHAR(32);

CREATE INDEX idx_coverage_map_access_owner ON coverage_map (access_owner);
