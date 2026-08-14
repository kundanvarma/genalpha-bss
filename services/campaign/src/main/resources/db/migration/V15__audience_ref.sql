-- GJ4: a campaign can target a saved insight Audience (a rule tree) instead
-- of a bare segment string. When set, the blast resolves members from it.
ALTER TABLE campaign ADD COLUMN audience_ref VARCHAR(64);
