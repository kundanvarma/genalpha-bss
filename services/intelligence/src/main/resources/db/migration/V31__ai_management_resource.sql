-- TMF915 AI Management: the REGISTERED half of the control plane's face.
-- The projected half (aiModel from the ledger, aiModelContract from the
-- governor) stays computed; the six declarative resources the standard
-- also carries — alarm, rule, aiContract, aiContractSpecification,
-- aiContractViolation, aiModelSpecification — plus explicitly registered
-- aiModel rows live here, one document per row, keyed by kind. The body is
-- the TMF representation as the caller sent it (minus id/href, which the
-- face derives), with the filterable attributes lifted into columns.
CREATE TABLE ai_management_resource (
    id          VARCHAR(36)   NOT NULL,
    tenant_id   VARCHAR(64)   NOT NULL,
    kind        VARCHAR(40)   NOT NULL,
    name        VARCHAR(255),
    state       VARCHAR(64),
    body        TEXT          NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    last_update TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_ai_management_resource PRIMARY KEY (id)
);
CREATE INDEX idx_ai_management_resource_tenant_kind ON ai_management_resource (tenant_id, kind);
