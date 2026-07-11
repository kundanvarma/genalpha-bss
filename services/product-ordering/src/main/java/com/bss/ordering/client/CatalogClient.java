package com.bss.ordering.client;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Read-side view of the product-catalog service (TMF620), as much of it as
 * order orchestration needs.
 */
public interface CatalogClient {

    Optional<OfferingRef> findOffering(String id);

    record OfferingRef(String id, String name, List<Map<String, Object>> productOfferingTerm) {

        public OfferingRef(String id, String name) {
            this(id, name, null);
        }

        /** Months of commitment declared by the offering's terms, or 0. */
        public int commitmentMonths() {
            if (productOfferingTerm == null) {
                return 0;
            }
            for (Map<String, Object> term : productOfferingTerm) {
                if (term.get("duration") instanceof Map<?, ?> duration
                        && duration.get("amount") instanceof Number amount
                        && "month".equalsIgnoreCase(String.valueOf(duration.get("units")))) {
                    return amount.intValue();
                }
            }
            return 0;
        }
    }
}
