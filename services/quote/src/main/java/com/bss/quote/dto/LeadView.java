package com.bss.quote.dto;

import com.bss.quote.api.ApiConstants;
import com.bss.quote.entity.SalesLead;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.time.OffsetDateTime;

/** A TMF699 salesLead: who knocked, from where, its score and grade, who it routed to, and the deal it became. */
@JsonPropertyOrder({"id", "href", "name", "description", "contactName", "contactEmail", "company", "source", "state",
        "score", "grade", "owner", "companySize", "salesOpportunity", "creationDate", "lastUpdate", "@type"})
public record LeadView(String id, String href, String name,
        @JsonInclude(JsonInclude.Include.NON_NULL) String description,
        @JsonInclude(JsonInclude.Include.NON_NULL) String contactName,
        @JsonInclude(JsonInclude.Include.NON_NULL) String contactEmail,
        @JsonInclude(JsonInclude.Include.NON_NULL) String company,
        String source, String state, int score,
        @JsonInclude(JsonInclude.Include.NON_NULL) String grade,
        @JsonInclude(JsonInclude.Include.NON_NULL) OwnerRef owner,
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer companySize,
        @JsonInclude(JsonInclude.Include.NON_NULL) EntityRef salesOpportunity,
        OffsetDateTime creationDate, OffsetDateTime lastUpdate,
        @JsonProperty("@type") String type) {

    public static LeadView of(SalesLead lead) {
        // the owner block appears once the lead routed somewhere (a name); the id rides along when known
        OwnerRef owner = lead.getOwnerName() == null ? null : new OwnerRef(lead.getOwnerId(), lead.getOwnerName());
        EntityRef opportunity = lead.getOpportunityId() == null ? null
                : EntityRef.at(lead.getOpportunityId(),
                        ApiConstants.SALES_BASE + "/salesOpportunity/" + lead.getOpportunityId());
        return new LeadView(lead.getId(), lead.getHref(), lead.getName(), lead.getDescription(),
                lead.getContactName(), lead.getContactEmail(), lead.getCompany(), lead.getSource(), lead.getState(),
                lead.getScore(), lead.getGrade(), owner, lead.getCompanySize(), opportunity,
                lead.getCreatedAt(), lead.getLastUpdate(), "SalesLead");
    }
}
