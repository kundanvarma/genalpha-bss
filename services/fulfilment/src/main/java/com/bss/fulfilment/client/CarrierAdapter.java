package com.bss.fulfilment.client;

import com.bss.fulfilment.entity.CarrierConfig;

import java.util.List;
import java.util.Map;

/**
 * One implementation per carrier wire (Helthjem, Bring/Posten, …). Selected per
 * tenant from {@link CarrierConfig}, resolved by {@link #name()} in the
 * {@link CarrierRegistry} — the same seam pattern as the CMS AssetProvider. Fail
 * behaviour is the caller's (the router swallows carrier errors to the manual flow).
 */
public interface CarrierAdapter {

    /** The name used in carrier_config and the registry ('helthjem', 'bring'). */
    String name();

    /** Book a parcel with this carrier at the chosen delivery method; returns the
     * carrier's refs (superset in Booking). */
    LogisticsClient.Booking book(CarrierConfig cfg, LogisticsClient.Booking request, DeliveryChoice delivery);

    /** Pickup points near a postcode (empty when the carrier has none / doesn't support it). */
    default List<Map<String, Object>> pickupPoints(CarrierConfig cfg, String postcode) {
        return List.of();
    }
}
