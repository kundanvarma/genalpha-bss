-- TMF646 Appointment: an installer visit booked into a time slot. Slots are
-- generated (business days, two-hour windows) and capacity-limited; an
-- appointment holds one unit of a slot until completed or cancelled.

CREATE TABLE appointment (
    id              VARCHAR(36)  NOT NULL,
    href            VARCHAR(255),
    status          VARCHAR(32)  NOT NULL,
    description     VARCHAR(2000),
    start_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    end_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    owner_party_id  VARCHAR(36),
    related_entity  VARCHAR(2000),
    place           VARCHAR(2000),
    creation_date   TIMESTAMP WITH TIME ZONE,
    last_update     TIMESTAMP WITH TIME ZONE,
    CONSTRAINT pk_appointment PRIMARY KEY (id)
);
CREATE INDEX idx_appointment_owner ON appointment (owner_party_id);
CREATE INDEX idx_appointment_start ON appointment (start_at);

CREATE TABLE event_outbox (
    id         VARCHAR(36)    NOT NULL,
    event_type VARCHAR(255)   NOT NULL,
    payload    VARCHAR(10000) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_event_outbox PRIMARY KEY (id)
);
