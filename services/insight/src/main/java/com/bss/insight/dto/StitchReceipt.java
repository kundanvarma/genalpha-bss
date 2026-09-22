package com.bss.insight.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** The login stitch: {stitched:false} without personalization consent, {stitched:true, partyId} with it. */
@JsonPropertyOrder({"stitched", "partyId"})
public record StitchReceipt(boolean stitched, @JsonInclude(JsonInclude.Include.NON_NULL) String partyId) {

    public static StitchReceipt refused() {
        return new StitchReceipt(false, null);
    }

    public static StitchReceipt to(String partyId) {
        return new StitchReceipt(true, partyId);
    }
}
