package com.bss.intelligence.signal;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** What the on-demand sweeps answer: counts, nothing personal. */
public final class SweepResults {

    private SweepResults() {
    }

    /** POST /signalSweep — signals classified and dropped across every tenant. */
    @JsonPropertyOrder({"classified", "dropped"})
    public record SignalSweepResult(int classified, int dropped) {
    }

    /** POST /cltvSweep — customers scored across every tenant. */
    @JsonPropertyOrder({"scored"})
    public record CltvSweepResult(int scored) {
    }

    /** POST /canaryProbe — canaries probed, and how many the provider completed. */
    @JsonPropertyOrder({"probed", "retentionSuspected"})
    public record CanaryProbeResult(int probed, int retentionSuspected) {
    }
}
