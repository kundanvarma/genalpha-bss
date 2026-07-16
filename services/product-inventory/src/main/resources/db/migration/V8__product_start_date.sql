-- TMF637 startDate: when the product began. Billing prorates a product
-- that started mid-period — a customer who arrived on the 16th pays for
-- half a month, not a whole one. Existing rows stay null = started
-- before any open period (full month, as before).
ALTER TABLE product ADD COLUMN start_date TIMESTAMP WITH TIME ZONE;
