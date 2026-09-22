package com.bss.campaign.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** What the push did: members in the segment, how many had an email to hash, how many the platform accepted. */
@JsonPropertyOrder({"segment", "audienceId", "members", "withEmail", "pushed", "schema"})
public record AudienceSyncResult(String segment, String audienceId, int members, int withEmail, int pushed,
        String schema) {
}
