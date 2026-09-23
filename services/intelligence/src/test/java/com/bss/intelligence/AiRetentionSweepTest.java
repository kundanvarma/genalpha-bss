package com.bss.intelligence;

import com.bss.intelligence.audit.AiAudit;
import com.bss.intelligence.audit.AiAuditRepository;
import com.bss.intelligence.audit.AiRetentionSweep;
import com.bss.intelligence.tick.TickGuard;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Two clocks: a raw row expires on its own bounded default; a redacted row
 * lives until an operator sets a window. Both dials off means nothing is
 * deleted — retention is a decision, never a surprise.
 */
class AiRetentionSweepTest {

    private static AiAudit row(boolean raw, OffsetDateTime when) {
        AiAudit a = new AiAudit();
        a.setId(java.util.UUID.randomUUID().toString());
        a.setRawExposure(raw);
        a.setCreatedAt(when);
        return a;
    }

    private record Harness(AiRetentionSweep sweep, List<AiAudit> deleted) { }

    @SuppressWarnings("unchecked")
    private static Harness harness(long generalSeconds, long rawSeconds,
            List<AiAudit> rawExpired, List<AiAudit> generalExpired) {
        AiAuditRepository audits = mock(AiAuditRepository.class);
        TickGuard guard = mock(TickGuard.class);
        when(guard.claim(anyString(), any(Duration.class))).thenReturn(true);
        when(audits.findByRawExposureTrueAndCreatedAtBefore(any())).thenReturn(rawExpired);
        when(audits.findByCreatedAtBefore(any())).thenReturn(generalExpired);
        List<AiAudit> deleted = new ArrayList<>();
        org.mockito.Mockito.doAnswer(inv -> {
            deleted.addAll((List<AiAudit>) inv.getArgument(0));
            return null;
        }).when(audits).deleteAll(any());
        return new Harness(new AiRetentionSweep(audits, guard, generalSeconds, rawSeconds), deleted);
    }

    @Test
    void rawRowsExpireOnTheirOwnBoundedDefault() {
        AiAudit old = row(true, OffsetDateTime.now().minusDays(40));
        Harness h = harness(0, 2592000, List.of(old), List.of());
        h.sweep().sweep();
        // the general dial is OFF and the raw row still went: that is the point
        assertThat(h.deleted()).containsExactly(old);
    }

    @Test
    void redactedRowsSurviveUntilAnOperatorSetsAWindow() {
        AiAudit redacted = row(false, OffsetDateTime.now().minusDays(400));
        Harness off = harness(0, 2592000, List.of(), List.of(redacted));
        off.sweep().sweep();
        assertThat(off.deleted()).isEmpty();

        Harness on = harness(86400, 2592000, List.of(), List.of(redacted));
        on.sweep().sweep();
        assertThat(on.deleted()).containsExactly(redacted);
    }

    @Test
    void bothDialsOffDeletesNothing() {
        Harness h = harness(0, 0,
                List.of(row(true, OffsetDateTime.now().minusYears(2))),
                List.of(row(false, OffsetDateTime.now().minusYears(2))));
        h.sweep().sweep();
        assertThat(h.deleted()).isEmpty();
    }

    @Test
    void anotherReplicaHoldingTheLeaseMeansThisOneDoesNothing() {
        AiAuditRepository audits = mock(AiAuditRepository.class);
        TickGuard guard = mock(TickGuard.class);
        when(guard.claim(anyString(), any(Duration.class))).thenReturn(false);
        new AiRetentionSweep(audits, guard, 86400, 2592000).sweep();
        org.mockito.Mockito.verify(audits, org.mockito.Mockito.never()).deleteAll(any());
    }
}
