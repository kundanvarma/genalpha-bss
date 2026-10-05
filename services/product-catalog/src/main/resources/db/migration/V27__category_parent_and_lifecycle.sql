-- Categories are the shop's shelves, and until now they were data nobody could
-- author: no console page, so the only way to add one was to edit a seed script
-- and rerun it (#155). Two columns are missing before a page can exist.
--
-- parent_id makes the flat list a two-level shelf. Every category is currently
-- top-level; nothing is backfilled, because a flat shelf is a real answer.
--
-- lifecycle_status lets a shelf be retired rather than deleted. Offerings point
-- at categories by id, so a delete would leave them pointing at nothing;
-- 'Active' is the state every existing row has been in all along.
ALTER TABLE category ADD COLUMN parent_id VARCHAR(36);
ALTER TABLE category ADD COLUMN lifecycle_status VARCHAR(32) DEFAULT 'Active';
UPDATE category SET lifecycle_status = 'Active' WHERE lifecycle_status IS NULL;
