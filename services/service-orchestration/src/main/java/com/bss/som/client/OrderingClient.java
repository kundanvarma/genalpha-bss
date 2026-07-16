package com.bss.som.client;

import java.util.Map;

/** The SOM's voice back to the BSS: mark a product order completed —
 * and, for the dealer channel, PLACE one on a customer's behalf with the
 * dealer attribution stamped on it. */
public interface OrderingClient {

    void complete(String productOrderId);

    /** Returns the created product order id. Fail-closed. */
    String create(Map<String, Object> productOrder);
}
