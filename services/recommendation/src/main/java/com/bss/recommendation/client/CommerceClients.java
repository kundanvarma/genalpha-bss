package com.bss.recommendation.client;

import java.util.List;
import java.util.Map;

/** What the recommender reads: what exists to sell, and what a party has. */
public final class CommerceClients {

    private CommerceClients() {
    }

    public interface CatalogClient {
        List<Map<String, Object>> activeOfferings();
    }

    public interface InsightClient {
        /** The customer's consented interests (category names), or empty. */
        List<String> interestsOf(String partyId);
    }

    public interface InventoryClient {
        List<Map<String, Object>> productsOf(String partyId);

        /** Tenant-wide adoption data — what the popularity ranker learns from. */
        List<Map<String, Object>> allProducts();
    }
}
