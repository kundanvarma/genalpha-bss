package com.bss.interaction.privacy;

import com.bss.interaction.entity.PartyInteraction;
import com.bss.interaction.repository.PartyInteractionRepository;
import com.bss.interaction.security.TenantContext;
import com.bss.interaction.tick.TickGuard;
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
 * RETENTION IS A CLOCK, NOT A PROMISE: interaction records older than
 * the retention window are deleted on schedule — data minimization
 * enforced by a tick, not remembered by a human. Disabled by default
 * (0 = keep forever) so no timeline-asserting suite loses its history;
 * production sets days, the privacy suite sets seconds.
 */
@Component
public class RetentionSweep {

    private static final Logger log = LoggerFactory.getLogger(RetentionSweep.class);

    private final PartyInteractionRepository interactions;
    private final TickGuard tickGuard;
    private final long retentionSeconds;

    public RetentionSweep(PartyInteractionRepository interactions, TickGuard tickGuard,
            @Value("${bss.retention.interactions-seconds:0}") long retentionSeconds) {
        this.interactions = interactions;
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
            List<PartyInteraction> expired = interactions.findByLastUpdateBefore(cutoff);
            if (!expired.isEmpty()) {
                interactions.deleteAll(expired);
                log.info("retention: {} interaction(s) older than {}s deleted",
                        expired.size(), retentionSeconds);
            }
        } finally {
            tickGuard.release("retention-sweep");
        }
    }
}
