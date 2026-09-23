package com.bss.som.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** TMF921-shaped intents: the ask, the stored intent, the feasibility report the network computes. */
public final class IntentDtos {

    private IntentDtos() {
    }

    @JsonPropertyOrder({"id", "href", "name", "description", "status", "expression", "relatedParty", "intentReport",
            "@type"})
    public record IntentView(String id, String href, String name,
            @JsonInclude(JsonInclude.Include.NON_NULL) String description,
            String status, ExpressionView expression,
            @JsonInclude(JsonInclude.Include.NON_NULL) List<PartyRef> relatedParty,
            @JsonInclude(JsonInclude.Include.NON_NULL) JsonNode intentReport,
            @JsonProperty("@type") String type) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"place", "latencyMs", "bandwidthMbps", "aiTokensMillions", "validFrom", "validUntil"})
    public record ExpressionView(String place, long latencyMs, long bandwidthMbps, Long aiTokensMillions,
            String validFrom, String validUntil) {
    }

    /** What the autonomous feasibility check answers; stored with the intent. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"feasible", "reason", "deliveryPoint", "proposedItems", "expectation"})
    public record IntentReport(boolean feasible, String reason, String deliveryPoint, List<ProposedItem> proposedItems,
            Expectation expectation) {

        public static IntentReport infeasible(String reason) {
            return new IntentReport(false, reason, null, null, null);
        }
    }

    @JsonPropertyOrder({"service", "offeringName", "reason"})
    public record ProposedItem(String service, String offeringName, String reason) {
    }

    @JsonPropertyOrder({"latencyMs", "bandwidthMbps", "slaBacked"})
    public record Expectation(long latencyMs, long bandwidthMbps, boolean slaBacked) {
    }

    /* ---------- request ---------- */

    /**
     * The ask: name + expression {place, latencyMs, bandwidthMbps, ...}. The
     * expression may also ride bare at the top level — the keys a record does
     * not declare are kept, and {@link #expressionOrSelf()} reads them there.
     */
    public record IntentRequest(String name, String description, List<PartyRef> relatedParty,
            Expression expression, @JsonAnySetter Map<String, Object> extensions) {

        public IntentRequest {
            extensions = extensions == null ? new LinkedHashMap<>() : extensions;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Expression(String place, Long latencyMs, Long bandwidthMbps, Long aiTokensMillions,
            String validFrom, String validUntil) {
    }
}
