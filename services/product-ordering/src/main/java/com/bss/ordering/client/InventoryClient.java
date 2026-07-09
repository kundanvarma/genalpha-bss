package com.bss.ordering.client;

import java.util.List;
import java.util.Map;

/**
 * Write-side view of the product-inventory service (TMF637): order completion
 * provisions the ordered product into inventory.
 */
public interface InventoryClient {

    void createProduct(NewProduct product);

    /** Field names match the inventory service's ProductDto JSON contract. */
    record NewProduct(
            String name,
            String status,
            Map<String, Object> productOffering,
            Map<String, Object> billingAccount,
            List<Map<String, Object>> relatedParty) {
    }
}
