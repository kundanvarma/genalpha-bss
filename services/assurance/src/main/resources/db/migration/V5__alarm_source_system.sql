-- TMF642: alarms carry the system that raised them (sourceSystemId) — a
-- mandatory attribute of the spec's Alarm resource, and an honest fact:
-- every alarm comes from somewhere.
ALTER TABLE alarm ADD COLUMN source_system_id VARCHAR(128);
