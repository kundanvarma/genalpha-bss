-- TMF653: test specifications as first-class rows, and tests that remember
-- their name and the spec they ran under. The built-in 'diagnose' spec
-- stays virtual (code, not data) so every tenant has it for free.
CREATE TABLE service_test_spec (
    id                VARCHAR(36) PRIMARY KEY,
    tenant_id         VARCHAR(64) NOT NULL DEFAULT 'genalpha',
    name              VARCHAR(255) NOT NULL,
    related_spec_json VARCHAR(2000),
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_service_test_spec_name ON service_test_spec (tenant_id, name);

ALTER TABLE service_test ADD COLUMN name VARCHAR(255);
ALTER TABLE service_test ADD COLUMN test_spec_json VARCHAR(1000);
