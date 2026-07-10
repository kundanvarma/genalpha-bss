-- TMF679 Product Offering Qualification: can this offering be delivered at
-- this place? Rules are data: an offering with serviceable_area rows is
-- gated and qualifies only where a postcode prefix matches; an offering with
-- none qualifies unconditionally.

CREATE TABLE serviceable_area (
    id                   VARCHAR(36)  NOT NULL,
    href                 VARCHAR(255),
    product_offering     VARCHAR(2000),
    product_offering_id  VARCHAR(36)  NOT NULL,
    postcode_prefix      VARCHAR(16)  NOT NULL,
    name                 VARCHAR(255),
    last_update          TIMESTAMP WITH TIME ZONE,
    CONSTRAINT pk_serviceable_area PRIMARY KEY (id)
);
CREATE INDEX idx_serviceable_area_offering ON serviceable_area (product_offering_id);

CREATE TABLE event_outbox (
    id         VARCHAR(36)    NOT NULL,
    event_type VARCHAR(255)   NOT NULL,
    payload    VARCHAR(10000) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_event_outbox PRIMARY KEY (id)
);
