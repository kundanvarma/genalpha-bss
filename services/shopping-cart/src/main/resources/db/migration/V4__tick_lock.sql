-- ONE REPLICA SPEAKS AT A TIME: scheduled ticks claim this row-lease
-- before running, so scaling the service out never double-fires a
-- dunning notice, a bill delivery, a campaign send or a commission.
CREATE TABLE tick_lock (
    name         VARCHAR(80) PRIMARY KEY,
    locked_until TIMESTAMP WITH TIME ZONE NOT NULL,
    locked_by    VARCHAR(120)             NOT NULL
);
