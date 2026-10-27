-- Swap and release (#356): an allocation that ends says why — `swapped` (the
-- household exchanged it for another benefit; counts toward the swap limit)
-- or `released` (given back) — and when, so "swaps this quarter" is a count,
-- not a guess.
ALTER TABLE choice_allocation ADD COLUMN IF NOT EXISTS end_reason VARCHAR(16);
ALTER TABLE choice_allocation ADD COLUMN IF NOT EXISTS ended_at TIMESTAMP WITH TIME ZONE;
