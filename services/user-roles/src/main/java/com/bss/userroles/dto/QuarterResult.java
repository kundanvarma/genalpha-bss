package com.bss.userroles.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * The compressed quarter a simulation ran — or the honest note that none
 * could run because there was no base to bill. Two shapes, two records.
 */
public sealed interface QuarterResult permits QuarterResult.SimulatedQuarter, QuarterResult.Skipped {

    /** Three cycles of +30 days, each followed by a REAL billing run; the assumptions ride on its face. */
    @JsonPropertyOrder({"@type", "cloneId", "cycle", "assumptions"})
    record SimulatedQuarter(@JsonProperty("@type") String type, String cloneId, List<Cycle> cycle,
            List<String> assumptions) implements QuarterResult {

        public static SimulatedQuarter of(String cloneId, List<Cycle> cycle) {
            return new SimulatedQuarter("SimulatedQuarter", cloneId, cycle, List.of(
                    "dates were compressed: 3 cycles of +30 days on the sandbox clock",
                    "recurring charges only — usage-dependent lines reflect seeded meters, not a lived quarter",
                    "billed by the SAME engine as production; no day billed twice across cycles"));
        }
    }

    /** One cycle: the clock after the move and billing's own answer (its document, or {@code {"error": …}}). */
    @JsonPropertyOrder({"cycle", "clockOffsetDays", "run"})
    record Cycle(int cycle, int clockOffsetDays, JsonNode run) {
    }

    record Skipped(String skipped) implements QuarterResult {

        public static final Skipped NO_BASE = new Skipped("no base mix given");
    }
}
