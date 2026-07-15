-- TMF699 Sales Management: the funnel BEFORE the quote. A salesLead is
-- the nominal result of an interaction with a PROSPECT (no party id yet —
-- they are not a customer); qualifying it mints a salesOpportunity, which
-- carries the revenue conversation onward to a quote and an order.
CREATE TABLE sales_lead (
    id            VARCHAR(36)  NOT NULL PRIMARY KEY,
    tenant_id     VARCHAR(64)  NOT NULL,
    href          VARCHAR(255),
    name          VARCHAR(255) NOT NULL,
    description   VARCHAR(2000),
    contact_name  VARCHAR(255),
    contact_email VARCHAR(255),
    company       VARCHAR(255),
    source        VARCHAR(64),
    state         VARCHAR(32)  NOT NULL,
    opportunity_id VARCHAR(36),
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    last_update   TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_sales_lead_tenant ON sales_lead (tenant_id, created_at);

CREATE TABLE sales_opportunity (
    id            VARCHAR(36)  NOT NULL PRIMARY KEY,
    tenant_id     VARCHAR(64)  NOT NULL,
    href          VARCHAR(255),
    name          VARCHAR(255) NOT NULL,
    description   VARCHAR(2000),
    lead_id       VARCHAR(36),
    state         VARCHAR(32)  NOT NULL,
    quote_ref     VARCHAR(36),
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    last_update   TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_sales_opportunity_tenant ON sales_opportunity (tenant_id, created_at);
