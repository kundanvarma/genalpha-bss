-- Installation scheduling becomes tenant-configurable, and capacity becomes
-- derived: a technician roster says who works when, and a slot's capacity
-- is the number of active technicians whose working hours cover it. A
-- tenant with no roster keeps the flat default_capacity per window.

CREATE TABLE schedule_config (
    tenant_id        VARCHAR(64)  NOT NULL,
    timezone         VARCHAR(64)  NOT NULL DEFAULT 'UTC',
    working_days     VARCHAR(64)  NOT NULL DEFAULT 'MON,TUE,WED,THU,FRI',
    slot_starts      VARCHAR(255) NOT NULL DEFAULT '09:00,11:00,13:00,15:00',
    slot_hours       INTEGER      NOT NULL DEFAULT 2,
    days_ahead       INTEGER      NOT NULL DEFAULT 7,
    default_capacity INTEGER      NOT NULL DEFAULT 3,
    last_update      TIMESTAMP WITH TIME ZONE,
    CONSTRAINT pk_schedule_config PRIMARY KEY (tenant_id)
);

CREATE TABLE technician (
    id            VARCHAR(36)  NOT NULL,
    tenant_id     VARCHAR(64)  NOT NULL,
    name          VARCHAR(255) NOT NULL,
    skills        VARCHAR(255),
    zone          VARCHAR(255),
    working_days  VARCHAR(64)  NOT NULL DEFAULT 'MON,TUE,WED,THU,FRI',
    start_time    VARCHAR(5)   NOT NULL DEFAULT '08:00',
    end_time      VARCHAR(5)   NOT NULL DEFAULT '17:00',
    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    creation_date TIMESTAMP WITH TIME ZONE,
    last_update   TIMESTAMP WITH TIME ZONE,
    CONSTRAINT pk_technician PRIMARY KEY (id)
);
CREATE INDEX idx_technician_tenant ON technician (tenant_id);
