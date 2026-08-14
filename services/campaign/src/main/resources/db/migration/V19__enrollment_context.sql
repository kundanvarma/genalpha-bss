-- Capture tokens from the triggering event (order.id, tracking.url, …) on the
-- enrollment, so a message sent days later can still reference why they entered.
ALTER TABLE journey_enrollment ADD COLUMN context_json VARCHAR(2000);
