package com.bss.som.seam;

import java.util.Map;

/**
 * Everything a seam adapter may know about the order it fulfils. The
 * {@code values} are the consumed characteristics the CFS→RFS edge names,
 * read by the executor off the product specification and the order item —
 * the executor is the only reader of the product spec; an adapter sees
 * exactly what the catalog said its RFS consumes.
 *
 * @param tenant         the tenant fulfilling the order
 * @param owner          the customer party id (may be null — every downstream check is null-safe)
 * @param serviceId      the inventory row being stood up (null for a billing-only CFS)
 * @param serviceOrderId the TMF641 service order for this item
 * @param offeringId     the ordered offering
 * @param offeringName   its display name (the service carries it)
 * @param productOrderId the parent TMF622 order
 * @param item           the order item as posted (place, product characteristics, relationships)
 * @param values         consumed characteristic name → value, first value wins, order item over spec
 * @param wishNumber     the shopper's chosen MSISDN, when the item carries one
 * @param deferred       whether the item ships or installs (carries a place) and is held inProgress
 */
public record SeamContext(String tenant, String owner, String serviceId, String serviceOrderId,
        String offeringId, String offeringName, String productOrderId,
        Map<String, Object> item, Map<String, String> values, String wishNumber, boolean deferred) {

    /** A consumed value, or null when the catalog did not carry it. */
    public String value(String name) {
        return values == null ? null : values.get(name);
    }

    /** A consumed value parsed as an integer, or null when absent or not a number. */
    public Integer intValue(String name) {
        String v = value(name);
        if (v == null || v.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
