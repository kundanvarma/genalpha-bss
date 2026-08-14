-- GJ5: a journey enrollment parked at a waitForEvent node remembers which
-- event advances it (before the node's timeout fires).
ALTER TABLE journey_enrollment ADD COLUMN await_event VARCHAR(160);
