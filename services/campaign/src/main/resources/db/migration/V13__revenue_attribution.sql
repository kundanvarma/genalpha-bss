-- REVENUE ATTRIBUTION: a conversion is not just a tick — it is the
-- monthly money the order carries (the catalog's recurring prices for
-- the ordered offerings). Lift then reads in currency per customer, not
-- only in points.
ALTER TABLE campaign_execution ADD COLUMN conversion_value NUMERIC(12,2);
ALTER TABLE journey_enrollment ADD COLUMN conversion_value NUMERIC(12,2);
