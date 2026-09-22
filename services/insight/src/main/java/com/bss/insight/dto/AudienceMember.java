package com.bss.insight.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * One resolved member. Which keys are present says which population it came
 * from: a customer {partyId[, email]}, a browser {visitorId[, partyId]}, a
 * prospect {prospectId, email, consent}; a frozen snapshot row carries
 * {partyId?, email?}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"visitorId", "partyId", "email", "prospectId", "consent"})
public record AudienceMember(String visitorId, String partyId, String email, String prospectId, String consent) {

    public static AudienceMember party(String partyId) {
        return new AudienceMember(null, partyId, null, null, null);
    }

    public static AudienceMember snapshot(String partyId, String email) {
        return new AudienceMember(null, partyId, email, null, null);
    }

    public static AudienceMember visitor(String visitorId, String partyId) {
        return new AudienceMember(visitorId, partyId, null, null, null);
    }

    public static AudienceMember prospect(String prospectId, String email, String consent) {
        return new AudienceMember(null, null, email, prospectId, consent);
    }

    /** The console's human label, filled in from the trait store. */
    public AudienceMember withEmail(String email) {
        return new AudienceMember(visitorId, partyId, email, prospectId, consent);
    }
}
