-- Segment campaigns have no event trigger — the segment IS the trigger.
ALTER TABLE campaign ALTER COLUMN trigger_event_type DROP NOT NULL;
