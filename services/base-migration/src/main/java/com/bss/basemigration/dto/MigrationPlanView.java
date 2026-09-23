package com.bss.basemigration.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * A migration wave as the desk reads it: what it moves (the matrix), who it
 * skips (eligibility), when it fires (the trigger), the law it obeys (the
 * jurisdiction pack), the rehearsal receipt that let it arm, and the wave
 * controls.
 *
 * <p>The four documents the operator authored stay {@link JsonNode}: they are
 * stored as written and answered as stored, and a record would re-order the
 * keys of every plan already on the books. Only the two keys the map used to
 * leave off are {@code NON_NULL}: {@code simulationAttachedAt} (no receipt
 * yet) and {@code customersDiscovered} (the arm receipt alone).
 */
@JsonPropertyOrder({"id", "href", "name", "state", "matrix", "eligibility", "trigger", "jurisdictionPack",
    "noticeDays", "grandfatheredPartyIds", "simulationRef", "simulationAttachedAt", "maxOrdersPerRun",
    "breakerThreshold", "consecutiveFailures", "createdAt", "lastUpdate", "@type", "customersDiscovered"})
public record MigrationPlanView(
        String id,
        String href,
        String name,
        String state,
        JsonNode matrix,
        JsonNode eligibility,
        JsonNode trigger,
        JsonNode jurisdictionPack,
        int noticeDays,
        List<String> grandfatheredPartyIds,
        String simulationRef,
        @JsonInclude(JsonInclude.Include.NON_NULL) String simulationAttachedAt,
        int maxOrdersPerRun,
        int breakerThreshold,
        int consecutiveFailures,
        String createdAt,
        String lastUpdate,
        @JsonProperty("@type") String type,
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer customersDiscovered) {

    /** The arm receipt: the plan as it now stands, plus how many the sweep found. */
    public MigrationPlanView withDiscovered(int discovered) {
        return new MigrationPlanView(id, href, name, state, matrix, eligibility, trigger, jurisdictionPack,
                noticeDays, grandfatheredPartyIds, simulationRef, simulationAttachedAt, maxOrdersPerRun,
                breakerThreshold, consecutiveFailures, createdAt, lastUpdate, type, discovered);
    }
}
