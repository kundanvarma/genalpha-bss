package com.bss.entitlement.dto;

import com.bss.entitlement.entity.EntitlementSubscriber;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * One IMSI binding on the BSS face: which line, which plan, what state the
 * line is in, and — on the detail reads — what it is entitled to in words.
 *
 * <p>Every key was always written, nulls included, so only the two the map
 * used to leave off are {@code NON_NULL}: {@code featureOverrides} (the
 * caller's own document, kept as it was stored, dropped when there is none
 * or when what is stored is not an object — the map path dropped it then
 * too) and {@code entitlements} (the detail reads only).
 */
@JsonPropertyOrder({"id", "imsi", "msisdn", "iccid", "partyId", "serviceId", "offeringId", "status",
    "imsProvisioned", "emergencyAddressConfirmed", "termsAccepted", "featureOverrides",
    "createdAt", "lastUpdate", "entitlements"})
public record SubscriberView(
        String id,
        String imsi,
        String msisdn,
        String iccid,
        String partyId,
        String serviceId,
        String offeringId,
        String status,
        boolean imsProvisioned,
        boolean emergencyAddressConfirmed,
        boolean termsAccepted,
        @JsonInclude(JsonInclude.Include.NON_NULL) Map<String, Object> featureOverrides,
        String createdAt,
        String lastUpdate,
        @JsonInclude(JsonInclude.Include.NON_NULL) EntitlementExplanation entitlements) {

    @SuppressWarnings("unchecked")
    public static SubscriberView of(EntitlementSubscriber s, ObjectMapper mapper, EntitlementExplanation explanation) {
        Map<String, Object> overrides = null;
        if (s.getFeatureOverrides() != null) {
            try {
                overrides = mapper.readValue(s.getFeatureOverrides(), Map.class);
            } catch (Exception ignore) {
                overrides = null; // not an object: the map path left the key off too
            }
        }
        return new SubscriberView(s.getId(), s.getImsi(), s.getMsisdn(), s.getIccid(), s.getPartyId(),
                s.getServiceId(), s.getOfferingId(), s.getStatus(), s.isImsProvisioned(),
                s.isEmergencyAddressConfirmed(), s.isTermsAccepted(), overrides,
                s.getCreatedAt() == null ? null : s.getCreatedAt().toString(),
                s.getLastUpdate() == null ? null : s.getLastUpdate().toString(), explanation);
    }

    /** The same row without the explanation — what the list and the receipts carry. */
    public SubscriberView withoutExplanation() {
        return entitlements == null ? this
                : new SubscriberView(id, imsi, msisdn, iccid, partyId, serviceId, offeringId, status,
                        imsProvisioned, emergencyAddressConfirmed, termsAccepted, featureOverrides,
                        createdAt, lastUpdate, null);
    }
}
