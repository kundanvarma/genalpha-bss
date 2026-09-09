-- JOURNEY AUTO-TUNING: message variants (arms) on a journey, the arm each
-- treated enrolment was dealt, the current traffic weights, and the tuning log
-- — every shift of traffic is a logged decision with its evidence.
ALTER TABLE journey ADD COLUMN arms VARCHAR(4000);
ALTER TABLE journey ADD COLUMN auto_tune BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE journey ADD COLUMN arm_weights VARCHAR(1000);
ALTER TABLE journey ADD COLUMN tuning_log VARCHAR(8000);
ALTER TABLE journey_enrollment ADD COLUMN arm VARCHAR(32);
