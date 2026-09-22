package com.bss.catalog.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/** The Agentic Commerce Protocol product feed: a projection of the active shelf in the shape shopping agents ingest. */
public record AcpProductFeed(List<Item> products) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"id", "title", "description", "item_category", "link", "availability", "price", "price_type",
            "recurring_period", "is_bundle"})
    public record Item(String id, String title, String description,
            @JsonProperty("item_category") String itemCategory, String link, String availability, Price price,
            @JsonProperty("price_type") String priceType, @JsonProperty("recurring_period") String recurringPeriod,
            @JsonProperty("is_bundle") Boolean isBundle) {
    }

    /** ACP money: the amount as a string, the currency code. */
    @JsonPropertyOrder({"amount", "currency"})
    public record Price(String amount, String currency) {
    }
}
