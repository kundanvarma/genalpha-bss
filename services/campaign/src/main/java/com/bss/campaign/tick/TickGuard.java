package com.bss.campaign.tick;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * ONE REPLICA SPEAKS AT A TIME. A scheduled tick that moves money or
 * sends mail must not double-fire when this service scales out. The
 * lock is a row: claim a lease in our own database, release when done,
 * expire on crash. No coordinator, no new dependency — the ShedLock
 * algorithm on the database we already trust. Claim and release run in
 * their own transactions (REQUIRES_NEW) so a lost claim can never
 * poison the tick's work.
 */
@Component
public class TickGuard {

    private final JdbcTemplate jdbc;
    private final String replica = UUID.randomUUID().toString();

    public TickGuard(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * True when THIS replica may run the tick now. Atomic in two moves:
     * an UPDATE steals an expired lease, an INSERT claims a virgin one;
     * whichever replica the database lets through owns the tick until
     * it releases — or the lease runs out, so a crashed holder never
     * wedges the fleet.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claim(String tick, Duration lease) {
        OffsetDateTime now = OffsetDateTime.now();
        int stolen = jdbc.update(
                "UPDATE tick_lock SET locked_until = ?, locked_by = ? "
                        + "WHERE name = ? AND locked_until < ?",
                now.plus(lease), replica, tick, now);
        if (stolen == 1) {
            return true;
        }
        try {
            return jdbc.update(
                    "INSERT INTO tick_lock (name, locked_until, locked_by) VALUES (?, ?, ?)",
                    tick, now.plus(lease), replica) == 1;
        } catch (org.springframework.dao.DataAccessException held) {
            return false; // another replica owns this tick right now
        }
    }

    /** Done: the lease ends now; the next tick, on any replica, may claim. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(String tick) {
        jdbc.update("UPDATE tick_lock SET locked_until = ? WHERE name = ? AND locked_by = ?",
                OffsetDateTime.now(), tick, replica);
    }

    /** A long tick HEARTBEATS: extend the lease while genuinely working,
     * so a crash frees it in one short lease, not one long one. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void extend(String tick, Duration lease) {
        jdbc.update("UPDATE tick_lock SET locked_until = ? WHERE name = ? AND locked_by = ?",
                OffsetDateTime.now().plus(lease), tick, replica);
    }
}
