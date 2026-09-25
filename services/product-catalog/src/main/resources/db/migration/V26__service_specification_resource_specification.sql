-- CFS step 2: a resource-facing service names the TMF634 resource specification
-- it realises (TMF633 ServiceSpecification.resourceSpecification[]). Stored
-- verbatim like the other reference lists on this table.
ALTER TABLE service_specification ADD COLUMN resource_specification VARCHAR(4000);
