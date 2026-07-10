package com.bss.ordering.client;

/**
 * Order-lifecycle view of the payment service (TMF676): an order carrying
 * payment refs is only accepted when each is an authorized payment belonging
 * to the order's owner; completion captures them, cancellation voids them.
 */
public interface PaymentClient {

    /** Empty message means valid; otherwise the reason the ref is unusable. */
    String validateAuthorized(String paymentId, String expectedOwnerPartyId, String orderId);

    void capture(String paymentId);

    void voidPayment(String paymentId);
}
