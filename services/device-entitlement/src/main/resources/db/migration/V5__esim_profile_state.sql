-- eSIM profiles come from the SM-DP+ (SGP.22 ES2+): the matching id the
-- activation code carries, the SM-DP+ address, and where the download stands
-- (ordered → downloading → installed | released | cancelled), reported by the
-- SM-DP+'s own handleDownloadProgressInfo notification.
ALTER TABLE companion_device ADD COLUMN matching_id VARCHAR(64);
ALTER TABLE companion_device ADD COLUMN smdp_address VARCHAR(128);
ALTER TABLE companion_device ADD COLUMN profile_state VARCHAR(16);
ALTER TABLE subscription_transfer ADD COLUMN matching_id VARCHAR(64);
ALTER TABLE subscription_transfer ADD COLUMN smdp_address VARCHAR(128);
ALTER TABLE subscription_transfer ADD COLUMN profile_state VARCHAR(16);
