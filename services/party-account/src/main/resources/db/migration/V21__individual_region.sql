-- Region as a first-class customer attribute (TMF632). A change emits
-- IndividualAttributeValueChangeEvent, so the CDP re-homes the customer between
-- region audiences automatically (single-valued trait = replace-on-change).
ALTER TABLE individual ADD COLUMN region VARCHAR(64);
