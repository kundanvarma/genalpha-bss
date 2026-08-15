-- Which ad platform an activation targets (meta | google | …).
ALTER TABLE activation_job ADD COLUMN destination VARCHAR(32);
