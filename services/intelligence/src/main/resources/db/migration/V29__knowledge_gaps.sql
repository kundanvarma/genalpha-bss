-- Questions the knowledge base could not answer: the content team's to-do list.
-- One row per tenant + normalised question, counted; the desk shows the top ones.
CREATE TABLE knowledge_gap (
    id            VARCHAR(36) PRIMARY KEY,
    tenant_id     VARCHAR(64) NOT NULL,
    question      VARCHAR(500) NOT NULL,
    context       VARCHAR(120),
    asked         INT NOT NULL DEFAULT 1,
    first_asked   TIMESTAMP WITH TIME ZONE NOT NULL,
    last_asked    TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE UNIQUE INDEX uq_knowledge_gap ON knowledge_gap (tenant_id, question);
