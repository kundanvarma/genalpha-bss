package com.bss.quote.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;

/** Request bodies on the TMF699 sales face — records, so a body cannot carry a field it does not declare. */
public final class SalesRequests {

    private SalesRequests() {
    }

    /** POST /salesLead — the open capture: any channel may knock. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LeadRequest(String name, String description, String contactName, String contactEmail,
            String company, String source, Integer companySize) {
    }

    /** PATCH /salesLead/{id} — qualified (with the account it is for, when known) or unqualified. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LeadPatch(String state, String partyId) {
    }

    /** PATCH /salesOpportunity/{id} — only the fields present change. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OpportunityPatch(String state, String stage, String closeReason, EntityRef quote,
            String forecastCategory, Integer probability, BigDecimal amount, String currency,
            String expectedCloseDate, String ownerId, String ownerName, String partyId, String description) {
    }

    /** POST /salesOpportunity/{id}/activity — a beat, or a next-step task when a dueDate is given. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ActivityRequest(String type, String note, String dueDate, String assignee) {
    }

    /** POST /salesOpportunity/quota. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record QuotaRequest(String ownerName, String quotaPeriod, BigDecimal amount, String team) {
    }

    /** POST /salesLead/scoringRule. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ScoringRuleRequest(String field, String value, Integer points) {
    }

    /** POST /salesLead/routingRule. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RoutingRuleRequest(Integer minScore, String assignee) {
    }
}
