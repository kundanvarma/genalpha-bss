-- Holds and plan changes (#357). A benefit may hold its Choice for a period
-- (a subsidised device promotion): held_until is fixed when the pick commits,
-- from the benefit's own hold capped by the law of the tenant's country, and
-- a release or swap before it is refused. A grant's Choices may change when
-- its plan changes; the row keeps the current number, ended_at when it ends.
ALTER TABLE choice_allocation ADD COLUMN IF NOT EXISTS hold_months INTEGER;
ALTER TABLE choice_allocation ADD COLUMN IF NOT EXISTS held_until TIMESTAMP WITH TIME ZONE;
