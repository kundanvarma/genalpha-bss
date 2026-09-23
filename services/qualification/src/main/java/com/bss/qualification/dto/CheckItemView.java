package com.bss.qualification.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/**
 * One verdict inside a checkServiceQualification. The key order is the order
 * the map path wrote it in: the verdict first, the id appended last by the
 * caller once the item's position is known. Every key a path never writes is
 * left off, as the map left it off. The id is the caller's own — echoed
 * back exactly as posted, number or string — so it stays open.
 *
 * <p>Both halves of the resource read this record: a fresh check builds it,
 * and a stored check parses its own rows back into it.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({"state", "qualificationItemResult", "service",
        "eligibilityUnavailabilityReason", "alternateServiceProposal", "id"})
public record CheckItemView(
        String state,
        String qualificationItemResult,
        @JsonInclude(JsonInclude.Include.NON_NULL) ServiceView service,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<Reason> eligibilityUnavailabilityReason,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        List<AlternateServiceProposal> alternateServiceProposal,
        @JsonInclude(JsonInclude.Include.NON_NULL) Object id) {

    public static CheckItemView qualified(ServiceView service) {
        return new CheckItemView("done", "qualified", service, null, null, null);
    }

    public static CheckItemView refused(Reason reason) {
        return new CheckItemView("done", "unqualified", null, List.of(reason), null, null);
    }

    public CheckItemView withAlternative(AlternateServiceProposal proposal) {
        return new CheckItemView(state, qualificationItemResult, service,
                eligibilityUnavailabilityReason, List.of(proposal), id);
    }

    public CheckItemView withId(Object itemId) {
        return new CheckItemView(state, qualificationItemResult, service,
                eligibilityUnavailabilityReason, alternateServiceProposal, itemId);
    }

    /** A verdict question, not a wire key — the map never wrote one. */
    @JsonIgnore
    public boolean isQualified() {
        return "qualified".equals(qualificationItemResult);
    }
}
