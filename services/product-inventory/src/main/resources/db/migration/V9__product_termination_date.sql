-- TMF637 terminationDate: when the product ended. The cease month bills
-- only its days, and next month bills nothing — leaving is priced as
-- fairly as arriving.
ALTER TABLE product ADD COLUMN termination_date TIMESTAMP WITH TIME ZONE;
