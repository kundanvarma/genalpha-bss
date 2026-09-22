package com.bss.billing.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** A consent upsert answers with the row it kept, or the withdrawal it honoured. */
public sealed interface ChannelConsentResult
        permits ChannelConsentResult.PartyBillingChannelView, ChannelConsentResult.Withdrawn {

    /** consented=false: the row is gone. */
    @JsonPropertyOrder({"partyId", "channel", "consented"})
    record Withdrawn(String partyId, String channel, boolean consented) implements ChannelConsentResult {
    }

    /** A party's consent to one delivery channel. */
    @JsonPropertyOrder({"id", "partyId", "channel", "aliasRef", "consentAt", "@type"})
    record PartyBillingChannelView(String id, String partyId, String channel, String aliasRef, String consentAt,
            @JsonProperty("@type") String type) implements ChannelConsentResult {
    }
}
