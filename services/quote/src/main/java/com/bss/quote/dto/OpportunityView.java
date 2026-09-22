package com.bss.quote.dto;

import com.bss.quote.api.ApiConstants;
import com.bss.quote.entity.SalesOpportunity;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * A TMF699 salesOpportunity as the desk works it: where it sits in the
 * pipeline and for how long, its value and forecast, who owns it, the quote
 * that sealed it, and — for the detail — its lines and its activity log.
 */
@JsonPropertyOrder({"id", "href", "name", "description", "salesLead", "state", "stage", "forecastCategory",
        "probability", "stageChangedAt", "daysInStage", "amount", "currency", "expectedCloseDate", "owner", "partyId",
        "closeReason", "quote", "items", "activities", "creationDate", "lastUpdate", "@type"})
public record OpportunityView(String id, String href, String name,
        @JsonInclude(JsonInclude.Include.NON_NULL) String description,
        @JsonInclude(JsonInclude.Include.NON_NULL) EntityRef salesLead,
        String state,
        @JsonInclude(JsonInclude.Include.NON_NULL) String stage,
        @JsonInclude(JsonInclude.Include.NON_NULL) String forecastCategory,
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer probability,
        @JsonInclude(JsonInclude.Include.NON_NULL) OffsetDateTime stageChangedAt,
        @JsonInclude(JsonInclude.Include.NON_NULL) Long daysInStage,
        @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal amount,
        @JsonInclude(JsonInclude.Include.NON_NULL) String currency,
        @JsonInclude(JsonInclude.Include.NON_NULL) String expectedCloseDate,
        @JsonInclude(JsonInclude.Include.NON_NULL) OwnerRef owner,
        @JsonInclude(JsonInclude.Include.NON_NULL) String partyId,
        @JsonInclude(JsonInclude.Include.NON_NULL) String closeReason,
        @JsonInclude(JsonInclude.Include.NON_NULL) EntityRef quote,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<OpportunityItemView> items,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<ActivityView> activities,
        OffsetDateTime creationDate, OffsetDateTime lastUpdate,
        @JsonProperty("@type") String type) {

    public static OpportunityView of(SalesOpportunity opp, List<OpportunityItemView> items,
            List<ActivityView> activities) {
        EntityRef lead = opp.getLeadId() == null ? null
                : EntityRef.at(opp.getLeadId(), ApiConstants.SALES_BASE + "/salesLead/" + opp.getLeadId());
        Long daysInStage = opp.getStageChangedAt() == null ? null
                : Duration.between(opp.getStageChangedAt(), OffsetDateTime.now()).toDays();
        EntityRef quote = opp.getQuoteRef() == null ? null
                : EntityRef.at(opp.getQuoteRef(), ApiConstants.BASE_PATH + "/quote/" + opp.getQuoteRef());
        return new OpportunityView(opp.getId(), opp.getHref(), opp.getName(), opp.getDescription(), lead,
                opp.getState(), opp.getStage(), opp.getForecastCategory(), opp.getProbability(),
                opp.getStageChangedAt(), daysInStage, opp.getAmount(), opp.getCurrency(),
                opp.getExpectedCloseDate() == null ? null : opp.getExpectedCloseDate().toString(),
                OwnerRef.ofNullable(opp.getOwnerId(), opp.getOwnerName()),
                opp.getPartyId(), opp.getCloseReason(), quote, items, activities,
                opp.getCreatedAt(), opp.getLastUpdate(), "SalesOpportunity");
    }
}
