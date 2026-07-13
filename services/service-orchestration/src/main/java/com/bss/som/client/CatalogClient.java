package com.bss.som.client;

import java.util.Optional;

/**
 * The one catalog fact the SOM needs: what KIND of thing is this offering?
 * The category decides fulfilment — a network line draws a number and a SIM,
 * a partner service mints an entitlement, a security product toggles a
 * feature, insurance is billing-only. Fails open to empty: an unreachable
 * catalog means "treat it as a line", the historical behavior.
 */
public interface CatalogClient {

    Optional<String> categoryOf(String offeringId);
}
