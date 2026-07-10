-- Variants (color, storage, ...) are TMF620 productSpecCharacteristic on the
-- specification, echoed verbatim — one offering per variant would explode the
-- catalog instead.

ALTER TABLE product_specification ADD COLUMN product_spec_characteristic VARCHAR(4000);
