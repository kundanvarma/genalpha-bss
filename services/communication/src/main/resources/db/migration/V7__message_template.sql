-- GJ2: reusable, localized, tokenized message templates. Journeys and
-- campaigns reference a template instead of pasting inline copy, so a piece
-- of copy is authored once, localized per locale, and changed in one place.

CREATE TABLE message_template (
    id            VARCHAR(36)  NOT NULL,
    href          VARCHAR(255),
    tenant_id     VARCHAR(64)  NOT NULL,
    name          VARCHAR(255) NOT NULL,
    channel       VARCHAR(32)  NOT NULL,
    locales       VARCHAR(8000),
    promotion_ref VARCHAR(64),
    created_at    TIMESTAMP WITH TIME ZONE,
    last_update   TIMESTAMP WITH TIME ZONE,
    CONSTRAINT pk_message_template PRIMARY KEY (id)
);
CREATE INDEX idx_message_template_tenant ON message_template (tenant_id);
