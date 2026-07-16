-- Mid-cycle plan change: the product remembers WHAT it was and WHEN it
-- switched, so the billing run can charge each plan for its own days
-- instead of pretending the month had one price.
ALTER TABLE product ADD COLUMN previous_offering VARCHAR(4000);
ALTER TABLE product ADD COLUMN offering_changed_at TIMESTAMP WITH TIME ZONE;
