-- TMF678 CustomerBillOnDemand: a request to generate a bill on demand, modeled
-- as a resource (the app cuts bills via the billing run; this is the spec-facing
-- on-demand resource, stored so it round-trips on GET).
CREATE TABLE customer_bill_on_demand (
    id           VARCHAR(36) PRIMARY KEY,
    href         VARCHAR(255),
    tenant_id    VARCHAR(64) NOT NULL DEFAULT 'genalpha',
    state        VARCHAR(32),
    payload_json VARCHAR(8000),
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    last_update  TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_cbod_tenant ON customer_bill_on_demand (tenant_id);
