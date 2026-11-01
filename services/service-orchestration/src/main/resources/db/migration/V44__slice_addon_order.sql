-- Who pays for a line's priority (#393, review): the open-ended priority add-on
-- (the "Priority on my line" Pick) is remembered beside the slice's owner, so
-- releasing it never strips a Priority plan's own priority, and a plan change
-- away from priority hands the slice to the add-on instead of dropping it.
ALTER TABLE service ADD COLUMN slice_addon_order_id VARCHAR(36);
