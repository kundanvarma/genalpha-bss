-- QUIET HOURS: the time-of-day half of the guardrail. Inside the window
-- (tenant-local time, may wrap midnight: 21:00 -> 08:00) marketing stays
-- silent — campaigns skip, journeys postpone to the window's end. Null
-- start/end = no quiet hours.
ALTER TABLE martech_setting ADD COLUMN quiet_start VARCHAR(5);
ALTER TABLE martech_setting ADD COLUMN quiet_end VARCHAR(5);
ALTER TABLE martech_setting ADD COLUMN time_zone VARCHAR(64);
