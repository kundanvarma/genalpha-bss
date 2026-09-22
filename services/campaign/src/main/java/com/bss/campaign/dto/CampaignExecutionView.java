package com.bss.campaign.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** One execution row: the customer reached (or held out) and when. {@code executedAt} is the clock's own text. */
@JsonPropertyOrder({"id", "party", "executedAt", "@type"})
public record CampaignExecutionView(String id, PartyRef party, String executedAt, @JsonProperty("@type") String type) {

    public record PartyRef(String id) {
    }
}
