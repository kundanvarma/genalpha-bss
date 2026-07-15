-- Temporary suspension (vacation hold, lost phone): the line pauses, the
-- number and SIM stay the customer's, and resume_at lets the hold end by
-- itself — a pause nobody remembers to lift becomes a churn letter.
ALTER TABLE service ADD COLUMN resume_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE service ADD COLUMN suspend_reason VARCHAR(32);
