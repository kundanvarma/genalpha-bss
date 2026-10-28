-- Benefit Choices (#354): a plan grants Choices into the household's pool and
-- a benefit costs Choices to pick. House fields beside TMF620 — both null on
-- every offering that is neither. Positive or nothing: a zero/negative count
-- is refused at the API, never stored.
ALTER TABLE product_offering ADD COLUMN IF NOT EXISTS choices_granted INTEGER;
ALTER TABLE product_offering ADD COLUMN IF NOT EXISTS choice_cost INTEGER;
