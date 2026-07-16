-- DUNNING: an overdue installment gets ONE reminder; still unpaid after
-- the grace period, the plan BREAKS and the remaining balance falls due
-- at once (the acceleration clause every plan has in the small print —
-- here it is a column, not small print).
ALTER TABLE installment_plan ADD COLUMN reminded_at TIMESTAMP WITH TIME ZONE;
