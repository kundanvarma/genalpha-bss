-- The SIM behind a numbered service: minted at activation alongside the
-- MSISDN. The PUK lives here (operator-side card data); the PIN lives on the
-- card itself and changes through the SIM-platform seam, never stored.
CREATE TABLE sim_card (
    iccid       VARCHAR(22) PRIMARY KEY,
    tenant_id   VARCHAR(64) NOT NULL DEFAULT 'genalpha',
    service_id  VARCHAR(36) NOT NULL,
    puk         VARCHAR(8) NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    last_update TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_sim_card_tenant ON sim_card (tenant_id);
CREATE INDEX idx_sim_card_service ON sim_card (service_id);
