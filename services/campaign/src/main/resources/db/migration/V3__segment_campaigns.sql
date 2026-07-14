-- Segment-triggered campaigns: instead of (or besides) a business-event
-- trigger, a campaign may name an INSIGHT SEGMENT — a browsed interest or
-- an audience the tenant's analytics computed. Executing the campaign
-- reaches every consented, stitched customer in the segment, once.
ALTER TABLE campaign ADD COLUMN segment_name VARCHAR(128);
