package com.bss.fulfilment.client;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * PostNord — the third carrier. It speaks the same Booking + Pickup Point shape
 * as Bring here (both are Nordic parcel networks with service points), so it
 * reuses that adapter's wire and only carries its own name/config. A production
 * PostNord adapter would swap in Shipment v3 + OAuth2; the seam is unchanged.
 */
@Component
public class PostnordCarrierAdapter extends BringCarrierAdapter {

    public PostnordCarrierAdapter(RestClient.Builder builder) {
        super(builder);
    }

    @Override
    public String name() {
        return "postnord";
    }
}
