-- The crew ceiling: an operator-set cap on how many DISTINCT workers may
-- hold live task leases at once. 0 = unlimited. Surge staffing grows the
-- crew with the queue — but never past the ceiling the operator chose.
ALTER TABLE ai_budget ADD COLUMN max_workers INTEGER DEFAULT 0 NOT NULL;
