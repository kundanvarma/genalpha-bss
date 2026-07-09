package com.bss.ordering.client;

import java.util.Optional;

/**
 * Read-side view of the product-catalog service (TMF620), as much of it as
 * order orchestration needs.
 */
public interface CatalogClient {

    Optional<OfferingRef> findOffering(String id);

    record OfferingRef(String id, String name) {
    }
}
