package com.bss.qualification.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;
import java.util.Map;

/**
 * The TMF679 verdict document. The key order is the one the {@code HashMap}
 * this replaces iterated in — deterministic, since Java's String hash is
 * specified — so the wire is unchanged. Each item is the caller's own
 * document with the verdict overlaid, and stays an open map.
 */
@JsonPropertyOrder({"productOfferingQualificationItem", "qualificationResult", "@type",
        "id", "state"})
public record PoqCheckResult(
        List<Map<String, Object>> productOfferingQualificationItem,
        String qualificationResult,
        @JsonProperty("@type") String type,
        String id,
        String state) {

    public static PoqCheckResult of(String id, String qualificationResult,
            List<Map<String, Object>> items) {
        return new PoqCheckResult(items, qualificationResult,
                "CheckProductOfferingQualification", id, "done");
    }
}
