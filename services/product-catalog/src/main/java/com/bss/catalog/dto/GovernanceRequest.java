package com.bss.catalog.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * The body of a governance door. Every door reads a subset: a note anywhere;
 * origin on request; until on hold; owner/done on ready; force, channel,
 * validFrom, validTo on launch; endDate on unlaunch. A channel entry is either
 * a registered id ("web") or a ref ({id}), so it stays a JSON node.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GovernanceRequest(String note, String origin, String until, String owner, Boolean done, Boolean force,
        List<JsonNode> channel, String validFrom, String validTo, String endDate) {

    public static final GovernanceRequest EMPTY = new GovernanceRequest(null, null, null, null, null, null, null, null, null, null);

    /** " — note" for a ledger line, or nothing. */
    public String noteSuffix() {
        return note == null ? "" : " — " + note;
    }
}
