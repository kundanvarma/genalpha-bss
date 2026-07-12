-- TMF635 CTK conformance: store the posted body on usage records and usage
-- specifications so spec fields (usageSpecification, usageSpecCharacteristic,
-- ...) round-trip on GET, and both become retrievable/CRUD resources.
ALTER TABLE usage_record ADD COLUMN payload_json VARCHAR(8000);
ALTER TABLE usage_specification ADD COLUMN payload_json VARCHAR(8000);
