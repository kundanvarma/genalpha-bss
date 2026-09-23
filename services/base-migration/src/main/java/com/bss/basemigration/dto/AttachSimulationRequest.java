package com.bss.basemigration.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * The rehearsal receipt a wave must show before it may arm: the id of a
 * report saved on the pricing simulator. Read exactly as the map read it —
 * whatever {@code String.valueOf} made of the posted value — so a number
 * still names a report and a JSON null is still "required".
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AttachSimulationRequest(JsonNode simulationRef) {

    public static final AttachSimulationRequest EMPTY = new AttachSimulationRequest(null);

    public String ref() {
        return MigrationPlanRequest.given(simulationRef) ? MigrationPlanRequest.text(simulationRef) : null;
    }
}
