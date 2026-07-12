-- B2B foundation: organizations form hierarchies (TMF632 parent relationship)
-- and individuals can belong to one (employer membership). Membership is what
-- lets ordering bill a company's lines together and the business console
-- scope a customer admin to their own people.
ALTER TABLE organization ADD COLUMN parent_id VARCHAR(36);
ALTER TABLE individual ADD COLUMN organization_id VARCHAR(36);
CREATE INDEX idx_individual_org ON individual (organization_id);
CREATE INDEX idx_organization_parent ON organization (parent_id);
