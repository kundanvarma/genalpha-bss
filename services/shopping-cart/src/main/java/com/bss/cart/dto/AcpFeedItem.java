package com.bss.cart.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * One priced row of the catalog's ACP product feed — the price every other
 * channel sees — read from the seam. Only what pricing a line needs is
 * declared; the feed's other columns are ignored.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AcpFeedItem(String id, String title, AcpMoney price,
        @JsonProperty("price_type") String priceType,
        @JsonProperty("recurring_period") String recurringPeriod) {

    /** The feed envelope: {@code {"products": [...]}}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Feed(List<AcpFeedItem> products) {
    }
}
