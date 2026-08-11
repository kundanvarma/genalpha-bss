package com.bss.som.client;

import java.util.Map;

/** The SOM's voice back to the BSS: mark a product order completed —
 * and, for the dealer channel, PLACE one on a customer's behalf with the
 * dealer attribution stamped on it. */
public interface OrderingClient {

    void complete(String productOrderId);

    /**
     * C1 — per-item fulfillment. Report ONE order item reaching a new state
     * (e.g. a digital service activated -> completed, a device awaiting delivery
     * -> inProgress); the ordering service rolls the parent order up. Fail-soft:
     * a reporting hiccup must not unwind a real activation.
     */
    void updateItemState(String productOrderId, String itemId, String state);

    /** Returns the created product order id. Fail-closed. */
    String create(Map<String, Object> productOrder);
}
