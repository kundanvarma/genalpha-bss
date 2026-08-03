package com.bss.process.service;

import com.bss.process.tick.TickGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** The clock on every owed task — fleet-safe under the tick lease. */
@Component
public class StuckSweepTick {

    private static final Logger log = LoggerFactory.getLogger(StuckSweepTick.class);

    private final ProcessFlowService service;
    private final TickGuard guard;

    public StuckSweepTick(ProcessFlowService service, TickGuard guard) {
        this.service = service;
        this.guard = guard;
    }

    @Scheduled(fixedDelayString = "${bss.process.sweep-interval-ms:15000}",
            initialDelayString = "${bss.process.sweep-initial-delay-ms:20000}")
    public void sweep() {
        if (!guard.claim("process-stuck-sweep", Duration.ofSeconds(10))) {
            return;
        }
        int failed = service.sweepStuck();
        if (failed > 0) {
            log.info("process sweep: {} flow(s) went FAILED", failed);
        }
    }
}
