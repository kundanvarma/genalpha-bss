-- THE COMMERCIAL SIMULATOR (P1): a price-change simulation is a REPORT with
-- receipts — the request that asked it, the answer it gave, and when. Reports
-- persist so decisions can later be calibrated against measured reality.
CREATE TABLE price_sim_report (
    id            VARCHAR(36)  PRIMARY KEY,
    tenant_id     VARCHAR(64)  NOT NULL DEFAULT 'genalpha',
    name          VARCHAR(255) NOT NULL,
    request_json  VARCHAR(4000) NOT NULL,
    report_json   TEXT         NOT NULL,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_price_sim_tenant ON price_sim_report (tenant_id, created_at);
