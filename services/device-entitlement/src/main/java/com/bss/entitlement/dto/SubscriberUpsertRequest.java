package com.bss.entitlement.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.TextNode;

/**
 * The activation / SIM-swap / plan-change hook's body. A record cannot carry
 * a field it does not declare, so nothing else on a posted document can
 * reach a column.
 *
 * <p>Every component is a {@link JsonNode} on purpose — the map path's
 * leniency IS the contract here, and a typed field would quietly change it:
 *
 * <ul>
 *   <li>the identity fields were read with {@code containsKey}, so an
 *       explicit JSON {@code null} CLEARS the column while an absent key
 *       leaves it alone — a nullable {@code String} cannot tell the two
 *       apart;</li>
 *   <li>the flags were read with {@code Boolean.parseBoolean(String.valueOf(v))},
 *       so the string {@code "true"} turns a flag on and the number {@code 1}
 *       does not — Jackson would have coerced both;</li>
 *   <li>{@code featureOverrides} is stored as the caller wrote it: a string
 *       verbatim, anything else re-serialised.</li>
 * </ul>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SubscriberUpsertRequest(
        JsonNode imsi,
        JsonNode msisdn,
        JsonNode iccid,
        JsonNode partyId,
        JsonNode serviceId,
        JsonNode offeringId,
        JsonNode status,
        JsonNode imsProvisioned,
        JsonNode emergencyAddressConfirmed,
        JsonNode termsAccepted,
        JsonNode featureOverrides) {

    public static final SubscriberUpsertRequest EMPTY = new SubscriberUpsertRequest(
            null, null, null, null, null, null, null, null, null, null, null);

    /** What the service flows post: an IMSI plus one or two flags. */
    public static SubscriberUpsertRequest ofFlow(String imsi, Boolean emergencyAddressConfirmed, boolean termsAccepted) {
        return new SubscriberUpsertRequest(TextNode.valueOf(imsi), null, null, null, null, null, null, null,
                emergencyAddressConfirmed == null ? null : BooleanNode.valueOf(emergencyAddressConfirmed),
                BooleanNode.valueOf(termsAccepted), null);
    }

    public String imsiText() {
        return text(imsi);
    }

    public String msisdnText() {
        return text(msisdn);
    }

    public String iccidText() {
        return text(iccid);
    }

    public String partyIdText() {
        return text(partyId);
    }

    public String serviceIdText() {
        return text(serviceId);
    }

    public String offeringIdText() {
        return text(offeringId);
    }

    /** {@code containsKey}: present at all, an explicit null included. */
    public boolean has(JsonNode node) {
        return node != null;
    }

    /** {@code get(...) != null}: present AND not a JSON null. */
    public static boolean given(JsonNode node) {
        return node != null && !node.isNull();
    }

    /** What {@code String.valueOf(map.get(k))} produced, for a value that is present. */
    public static String scalar(JsonNode node) {
        return node == null || node.isNull() ? null : node.isTextual() ? node.textValue() : node.toString();
    }

    /** What {@code Boolean.parseBoolean(String.valueOf(v))} produced: only "true", in any case. */
    public static boolean truthy(JsonNode node) {
        return Boolean.parseBoolean(scalar(node));
    }

    private static String text(JsonNode node) {
        return scalar(node);
    }
}
