package com.bss.som.client;

import java.util.Map;

/** The SOM's voice back to the BSS: mark a product order completed. */
public interface OrderingClient {

    void complete(String productOrderId);
}
