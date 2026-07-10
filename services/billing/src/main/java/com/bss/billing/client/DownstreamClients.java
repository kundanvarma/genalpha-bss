package com.bss.billing.client;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * The billing run's view of the rest of the BSS: what is provisioned
 * (inventory), what it costs (catalog), and how bills get settled (payment).
 * Interfaces so tests mock them; Rest implementations talk through the
 * machine identity.
 */
public final class DownstreamClients {

    private DownstreamClients() {
    }

    public interface InventoryClient {
        /** All active products, fully paged. */
        List<Map<String, Object>> activeProducts();
    }

    public interface CatalogClient {
        Map<String, Object> offering(String id);

        Map<String, Object> price(String id);
    }

    public interface PaymentClient {
        /** Empty message means valid; otherwise the reason the ref is unusable. */
        String validateAuthorized(String paymentId, String expectedOwnerPartyId, BigDecimal minimumAmount);

        void capture(String paymentId);
    }
}
