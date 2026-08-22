-- L3 — validFor: a Launched offering outside its window simply is not
-- served; announced_at is the launch-event dedupe (the emitter fires once
-- when a window opens).
ALTER TABLE product_offering ADD COLUMN valid_from TIMESTAMP WITH TIME ZONE;
ALTER TABLE product_offering ADD COLUMN valid_to TIMESTAMP WITH TIME ZONE;
ALTER TABLE product_offering ADD COLUMN announced_at TIMESTAMP WITH TIME ZONE;
