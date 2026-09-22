package com.bss.userroles.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/** "Your business on our BSS": the sandbox minted, the shelf built, the twins seeded, the quarter run. */
@JsonPropertyOrder({"@type", "sandboxId", "prospect", "shelf", "twinBase", "quarter", "assumptions"})
public record ProspectSimulation(@JsonProperty("@type") String type, String sandboxId, String prospect, int shelf,
        int twinBase, QuarterResult quarter, List<String> assumptions) {

    public static ProspectSimulation of(String sandboxId, String prospect, int shelf, int twinBase,
            QuarterResult quarter) {
        return new ProspectSimulation("ProspectSimulation", sandboxId, prospect, shelf, twinBase, quarter, List.of(
                "input was PUBLIC only: the prospect's price list and an assumed mix — none of their data was touched",
                "billed by the SAME engines that cut production bills, over a compressed quarter",
                "the sandbox is walled from the outside world and dies with its realm"));
    }
}
