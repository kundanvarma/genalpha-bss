package com.bss.ordering.client;

/**
 * Order-lifecycle view of the product-stock service (TMF687): placing an
 * order reserves stock, completing consumes it, cancelling releases it.
 * Offerings without a stock record are not stock-managed and reserve as OK.
 */
public interface StockClient {

    ReserveOutcome reserve(String productOfferingId, String offeringName, int quantity, String orderId);

    /** The same, naming the configured variant (colour, storage) so a per-variant stock row is the one reserved. */
    default ReserveOutcome reserve(String productOfferingId, String offeringName, int quantity, String orderId,
            java.util.List<java.util.Map<String, Object>> characteristics) {
        return reserve(productOfferingId, offeringName, quantity, orderId);
    }

    void release(String orderId);

    void consume(String orderId);

    record ReserveOutcome(boolean ok, String message) {
        public static ReserveOutcome reserved() {
            return new ReserveOutcome(true, null);
        }

        public static ReserveOutcome insufficient(String message) {
            return new ReserveOutcome(false, message);
        }
    }
}
