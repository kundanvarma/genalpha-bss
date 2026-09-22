package com.bss.intelligence.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/** What the channels post to the copilots. Unknown fields are ignored, never bound. */
public final class CopilotRequests {

    private CopilotRequests() {
    }

    /** A B2B sales ask in plain language. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record IntentAsk(String ask) {
    }

    /** Whose next best offer. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record NextBestOfferRequest(String partyId) {
    }

    /** A question for the knowledge base, optionally from a named screen ("pane:approvals"). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record KnowledgeAskRequest(String question, String context) {
    }

    /** A one-line brief for a campaign message. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CopyBrief(String brief, String brandName, String triggerEventType, String promotionCode) {
    }

    /** A one-line brief for a journey, with optional stages. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record JourneyBrief(String brief, String brandName, List<StageSpec> stages) {

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record StageSpec(String stage, String intent) {
        }
    }

    /** An advisor proposal a human chose to adopt; the price is posted to the catalog as written. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AdoptProposal(String name, String description, JsonNode price, String decisionId) {
    }

    /** A chat-to-create conversation: the turns so far and, for the product copilot,
     * the catalog context the owner's token can already see (kept open). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CopilotChatRequest(List<Turn> messages, JsonNode catalog) {

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Turn(String role, String content) {

            public boolean fromOwner() {
                return "owner".equalsIgnoreCase(String.valueOf(role)) || "user".equalsIgnoreCase(String.valueOf(role));
            }

            public String contentOrEmpty() {
                return content == null ? "" : content;
            }
        }
    }
}
