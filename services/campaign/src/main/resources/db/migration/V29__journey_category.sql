-- A journey is marketing unless it says otherwise. A TRANSACTIONAL journey
-- carries a service notice the customer asked for by being a customer
-- ("you're running low", "your line is suspended"): it is not parked by
-- marketing quiet hours, does not spend the marketing frequency budget and
-- is not counted as a marketing touch. Opt-outs still apply per channel in
-- the communication component.
ALTER TABLE journey ADD COLUMN category VARCHAR(32) NOT NULL DEFAULT 'marketing';
