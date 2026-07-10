package com.bss.ordering.client;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Stock integration switched off (tests, stock-less deployments): everything reserves. */
@Component
@ConditionalOnProperty(name = "bss.stock.enabled", havingValue = "false")
public class NoopStockClient implements StockClient {

    @Override
    public ReserveOutcome reserve(String productOfferingId, String offeringName, int quantity, String orderId) {
        return ReserveOutcome.reserved();
    }

    @Override
    public void release(String orderId) {
    }

    @Override
    public void consume(String orderId) {
    }
}
