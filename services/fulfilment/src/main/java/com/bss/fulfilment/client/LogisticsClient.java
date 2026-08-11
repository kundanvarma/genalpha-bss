package com.bss.fulfilment.client;

/**
 * The logistics seam (TMF700 sits in front of it): book a physical parcel with
 * a carrier and learn its tracking number. Carrier-agnostic — Helthjem,
 * Posten/Bring and PostNord/Strålfors all plug in behind this one interface,
 * each mapping our service levels and normalized statuses to their own.
 *
 * Fail-open by contract: if the seam is unconfigured (blank base URL) or the
 * carrier is unreachable, {@link #book} returns null and fulfilment falls back
 * to the manual warehouse flow — a carrier outage never blocks a shipment.
 */
public interface LogisticsClient {

    /** Book a parcel; returns the carrier's refs, or null if off/unavailable. */
    Booking book(Booking request);

    /** Poll a parcel's current status (the poll-style carrier path), or null. */
    Tracking track(String trackingNumber);

    /** What we send to book, and what the carrier returns (superset of fields). */
    record Booking(String shippingOrderId, String tenantId, String callbackUrl,
                   String recipientPartyId, String serviceLevel,
                   String carrier, String carrierShipmentId, String trackingNumber,
                   String trackingUrl, String labelRef) {
        public static Booking request(String shippingOrderId, String tenantId, String callbackUrl,
                               String recipientPartyId, String serviceLevel) {
            return new Booking(shippingOrderId, tenantId, callbackUrl, recipientPartyId,
                    serviceLevel, null, null, null, null, null);
        }
    }

    /** Normalized status vocabulary (adapters map the carrier's raw words to this). */
    record Tracking(String status, String carrierStatus) {
    }
}
