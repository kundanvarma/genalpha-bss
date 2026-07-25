-- P2: posting-key config values (tax percent, currency-per-point), the
-- period-close marker, and the fleet-safe tick lease for the loyalty accrual.
ALTER TABLE account_mapping ADD COLUMN config_value NUMERIC(14,6);

CREATE TABLE period_close (
    tenant_id      VARCHAR(64) PRIMARY KEY,
    closed_through DATE NOT NULL,
    closed_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE tick_lock (
    name         VARCHAR(80) PRIMARY KEY,
    locked_until TIMESTAMP WITH TIME ZONE NOT NULL,
    locked_by    VARCHAR(120)             NOT NULL
);
