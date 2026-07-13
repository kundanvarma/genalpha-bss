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

    public interface UsageClient {
        /** Rate a party's usage for the period; returns the period's charges. */
        List<Map<String, Object>> rateForParty(String ownerPartyId, String periodStart, String periodEnd);
    }

    public interface PromotionClient {
        /** The customer's earned discounts (promotion redemptions). */
        List<Map<String, Object>> redemptionsFor(String ownerPartyId);
    }

    public interface PricingClient {
        /**
         * Data-authored pricing rules applied to a recurring subtotal in context.
         * Each entry is {label, type, value, amount(signed)}. Empty on outage —
         * pricing rules never block a bill, the base price simply stands.
         */
        List<Map<String, Object>> adjustments(Map<String, Object> context);
    }

    public interface OrgClient {
        /**
         * The organization a party belongs to, or empty. Consolidated B2B
         * billing keys on this; outages fall back to per-person bills.
         */
        java.util.Optional<String> organizationOf(String partyId);

        /**
         * The organization's device co-pay policy as {value, unit}, or empty
         * when the company has none. Above the allowance, a device's monthly
         * charge moves to the employee's personal bill.
         */
        java.util.Optional<Map<String, Object>> deviceAllowanceOf(String orgId);
    }

    public interface PaymentClient {
        /** Empty message means valid; otherwise the reason the ref is unusable. */
        String validateAuthorized(String paymentId, String expectedOwnerPartyId, BigDecimal minimumAmount);

        void capture(String paymentId);
    }
}
