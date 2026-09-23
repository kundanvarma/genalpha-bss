package com.bss.intelligence.audit;

import com.bss.intelligence.security.TenantContext;
import com.bss.intelligence.tick.TickGuard;
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
 * RETENTION FOR THE AI LEDGER — two clocks, because the rows are not alike.
 *
 * A REDACTED row holds placeholders, not people: {@code <name#1> asked about
 * <phone#2>}. It is an audit record of a model call, it is the evidence that
 * the governor did its job, and keeping it is the point. Its window is a dial
 * an operator sets, off by default (0 = keep), exactly like every other
 * retention dial in this repository.
 *
 * A RAW row is different in kind. A tenant that switched on
 * {@code ai-raw-exposure} sent the unredacted prompt to the provider, and the
 * ledger kept that prompt. That is real personal data sitting in a table, and
 * "we kept it until somebody remembered to delete it" is not a retention
 * policy. So raw rows expire on their own clock, bounded by DEFAULT — the
 * dangerous mode comes with its own expiry rather than depending on the
 * operator who turned it on also remembering to configure a sweep.
 *
 * In practice no tenant ships with raw exposure on (ops/arch/claims.sh refuses
 * it), so this clock is a safety net for the day someone deliberately opts in.
 *
 * What this does NOT do, stated plainly here and in docs/privacy.md: erase one
 * person's AI records on request. {@code ai_audit} carries no subject link —
 * no column says whose call a row was — so the question cannot be answered
 * from this table. Retention is the control that is actually available; a
 * subject link is a schema change and a separate piece of work.
 */
@Component
public class AiRetentionSweep {

    private static final Logger log = LoggerFactory.getLogger(AiRetentionSweep.class);

    private final AiAuditRepository audits;
    private final TickGuard tickGuard;
    private final long generalSeconds;
    private final long rawSeconds;

    public AiRetentionSweep(AiAuditRepository audits, TickGuard tickGuard,
            @Value("${bss.retention.ai-audit-seconds:0}") long generalSeconds,
            @Value("${bss.retention.ai-audit-raw-seconds:2592000}") long rawSeconds) {
        this.audits = audits;
        this.tickGuard = tickGuard;
        this.generalSeconds = generalSeconds;
        this.rawSeconds = rawSeconds;
    }

    @Scheduled(fixedDelayString = "${bss.retention.sweep-ms:3600000}")
    @Transactional
    public void sweep() {
        if (generalSeconds <= 0 && rawSeconds <= 0) {
            return; // both dials off
        }
        // one replica sweeps: the same row lease every other scheduled mutator uses
        if (!tickGuard.claim("ai-retention-sweep", Duration.ofSeconds(60))) {
            return;
        }
        try (TenantContext ignored = TenantContext.actAsSystem()) {
            OffsetDateTime now = OffsetDateTime.now();
            if (rawSeconds > 0) {
                delete(audits.findByRawExposureTrueAndCreatedAtBefore(now.minusSeconds(rawSeconds)),
                        "raw-exposure", rawSeconds);
            }
            if (generalSeconds > 0) {
                delete(audits.findByCreatedAtBefore(now.minusSeconds(generalSeconds)),
                        "ledger", generalSeconds);
            }
        } finally {
            tickGuard.release("ai-retention-sweep");
        }
    }

    private void delete(List<AiAudit> expired, String what, long window) {
        if (expired.isEmpty()) {
            return;
        }
        audits.deleteAll(expired);
        log.info("retention: {} AI {} row(s) older than {}s deleted", expired.size(), what, window);
    }
}
