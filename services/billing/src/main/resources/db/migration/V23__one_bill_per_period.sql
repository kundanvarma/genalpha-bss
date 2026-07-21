-- THE DATABASE REFUSES A DOUBLE BILL: one bill per account per period,
-- as a constraint rather than a convention. The run's own idempotency
-- check makes this a no-op in the happy path; the index is what stands
-- when two runs, two replicas or two bugs race for the same period.
CREATE UNIQUE INDEX uq_bill_owner_period
    ON customer_bill (tenant_id, owner_party_id, period_start);
