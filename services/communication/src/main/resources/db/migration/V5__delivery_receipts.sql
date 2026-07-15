-- The ESP answers back: delivery receipts stamp the message with what the
-- provider saw (delivered, bounced…), and a hard bounce/complaint puts the
-- address on the tenant's suppression list — future ESP sends skip it
-- (the in-app inbox still gets the message; email just stops knocking on
-- a door that bounced).
ALTER TABLE communication_message ADD COLUMN delivery_status VARCHAR(32);

CREATE TABLE suppression (
    id         VARCHAR(36)  NOT NULL PRIMARY KEY,
    tenant_id  VARCHAR(64)  NOT NULL,
    email      VARCHAR(255) NOT NULL,
    reason     VARCHAR(64)  NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_suppression UNIQUE (tenant_id, email)
);
