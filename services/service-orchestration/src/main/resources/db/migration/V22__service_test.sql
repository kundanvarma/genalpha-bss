-- TMF653: the CSR "Diagnose" becomes a first-class serviceTest with
-- HISTORY — every run persisted with its verdict and findings.
CREATE TABLE service_test (
    id             VARCHAR(36) PRIMARY KEY,
    tenant_id      VARCHAR(64) NOT NULL DEFAULT 'genalpha',
    service_id     VARCHAR(36) NOT NULL,
    owner_party_id VARCHAR(64),
    verdict        VARCHAR(32),
    findings_json  VARCHAR(4000),
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_service_test_service ON service_test (tenant_id, service_id);
