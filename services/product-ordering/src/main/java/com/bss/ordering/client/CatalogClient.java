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

    record OfferingRef(String id, String name, List<Map<String, Object>> productOfferingTerm,
            boolean requiresVerifiedIdentity, Boolean isBundle,
            List<Map<String, Object>> bundledProductOffering) {

        public OfferingRef(String id, String name) {
            this(id, name, null, false, false, null);
        }

        public OfferingRef(String id, String name, List<Map<String, Object>> productOfferingTerm,
                boolean requiresVerifiedIdentity) {
            this(id, name, productOfferingTerm, requiresVerifiedIdentity, false, null);
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

        public boolean bundle() {
            return Boolean.TRUE.equals(isBundle);
        }

        /**
         * Choice groups in this bundle (TMF620): bundle members carrying an
         * {@code options} array, from which the customer picks between the
         * group's lower and upper limits ("pick 2 of 4").
         */
        public List<Map<String, Object>> choiceGroups() {
            if (bundledProductOffering == null) {
                return List.of();
            }
            List<Map<String, Object>> groups = new java.util.ArrayList<>();
            for (Map<String, Object> member : bundledProductOffering) {
                if (member.get("options") instanceof List) {
                    groups.add(member);
                }
            }
            return groups;
        }
    }
}
