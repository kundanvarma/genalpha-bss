package com.bss.basemigration.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * What one plan's trigger scan did. The three trigger kinds answer three
 * honestly different documents, so they are three arms — never one record
 * with half its keys null.
 */
public sealed interface TriggerScanResult {

    /**
     * A bulk plan has nothing to scan: it discovered its cohort when it
     * armed. Key order is the order the answer already has.
     */
    @JsonPropertyOrder({"note", "triggerType", "scheduled"})
    record Bulk(String note, String triggerType, int scheduled) implements TriggerScanResult {

        public static Bulk of(String triggerType) {
            return new Bulk("bulk plans discover on arm; nothing to scan", triggerType, 0);
        }
    }

    /** An age-threshold plan: how many birthdays were queued, how many lapsed to grandfathered. */
    @JsonPropertyOrder({"triggerType", "strategy", "scheduled", "grandfathered"})
    record Age(String triggerType, String strategy, int scheduled, int grandfathered)
            implements TriggerScanResult {
    }

    /** A promo-expiry plan: the disclosed end date, whether it is due, and what that queued. */
    @JsonPropertyOrder({"triggerType", "endDate", "due", "scheduled"})
    record Promo(String triggerType, String endDate, boolean due, int scheduled)
            implements TriggerScanResult {
    }
}
