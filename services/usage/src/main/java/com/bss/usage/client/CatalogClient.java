package com.bss.usage.client;

import java.util.Map;

/** The product levers behind a plan: gifting and rollover are PRODUCT
 * configuration (some CSPs frame it exactly this way), read from the offering's
 * spec characteristics — never hardcoded. */
public interface CatalogClient {

    /** The offering's spec characteristics as {name -> first value}, empty on any miss. */
    Map<String, String> specCharacteristicsOf(String offeringId);
}
