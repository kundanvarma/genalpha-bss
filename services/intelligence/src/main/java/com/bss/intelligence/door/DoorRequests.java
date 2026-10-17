package com.bss.intelligence.door;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.OffsetDateTime;

/** The model door's typed edges (#283). */
public final class DoorRequests {

    private DoorRequests() {
    }

    /** Issue a key for one worker. The tenant is the CALLER's, from its token. */
    public record IssueKey(String badge, String workerId, String job) {
    }

    /** The key, shown once. Only its hash is kept. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record IssuedKey(String id, String key, String badge, String workerId, String job,
            OffsetDateTime createdAt) {
    }

    /** What the door hands back to the worker: the provider's status and body, verbatim. */
    public record DoorReply(int status, String body) {
    }
}
