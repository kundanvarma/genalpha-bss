package com.bss.fulfilment.client;

/**
 * How the shopper chose to receive the parcel (C-P3), carried on the delivery
 * place from checkout: home delivery, or a pickup point / locker at a named
 * carrier. HOME is the default when nothing was chosen.
 */
public record DeliveryChoice(String method, String carrier, String pickupPointId, String pickupPointName) {

    public static final DeliveryChoice HOME = new DeliveryChoice("home", null, null, null);

    public boolean isPickup() {
        return "pickupPoint".equals(method) || "locker".equals(method);
    }
}
