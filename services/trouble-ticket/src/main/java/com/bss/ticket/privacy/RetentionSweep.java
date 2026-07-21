package com.bss.ticket.privacy;

import com.bss.ticket.entity.TroubleTicket;
import com.bss.ticket.repository.TroubleTicketRepository;
import com.bss.ticket.security.TenantContext;
import com.bss.ticket.tick.TickGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * RETENTION IS A CLOCK: settled tickets (resolved/closed/cancelled)
 * older than the window are deleted on schedule. OPEN tickets are never
 * touched — retention minimizes history, it does not lose live work.
 * Disabled by default (0 = keep forever).
 */
@Component
public class RetentionSweep {

    private static final Logger log = LoggerFactory.getLogger(RetentionSweep.class);
    private static final List<String> SETTLED =
            List.of(TroubleTicket.RESOLVED, TroubleTicket.CLOSED, "cancelled");

    private final TroubleTicketRepository tickets;
    private final TickGuard tickGuard;
    private final long retentionSeconds;

    public RetentionSweep(TroubleTicketRepository tickets, TickGuard tickGuard,
            @Value("${bss.retention.tickets-seconds:0}") long retentionSeconds) {
        this.tickets = tickets;
        this.tickGuard = tickGuard;
        this.retentionSeconds = retentionSeconds;
    }

    @Scheduled(fixedDelayString = "${bss.retention.sweep-ms:60000}")
    @Transactional
    public void sweep() {
        if (retentionSeconds <= 0) {
            return; // retention off — the dial decides, not the code
        }
        if (!tickGuard.claim("retention-sweep", Duration.ofSeconds(60))) {
            return; // another replica sweeps
        }
        try (TenantContext ignored = TenantContext.actAsSystem()) {
            OffsetDateTime cutoff = OffsetDateTime.now().minusSeconds(retentionSeconds);
            List<TroubleTicket> expired =
                    tickets.findByStatusInAndLastUpdateBefore(SETTLED, cutoff);
            if (!expired.isEmpty()) {
                tickets.deleteAll(expired);
                log.info("retention: {} settled ticket(s) older than {}s deleted",
                        expired.size(), retentionSeconds);
            }
        } finally {
            tickGuard.release("retention-sweep");
        }
    }
}
