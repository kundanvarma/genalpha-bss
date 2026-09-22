package com.bss.cart.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * One priced ACP line, as sent to the agent and as stored on the session.
 * A plain offering carries the feed's price; a configured bundle carries
 * the configurator's verdict beside it — {@code configuration} is the
 * order-ready echo and {@code price_line} the priced parts, both foreign
 * TMF760 documents this service never re-shapes.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({"id", "item", "quantity", "unit_price", "price_type", "recurring_period", "due_now",
        "configuration", "price_line"})
public record LineItem(String id, Item item, int quantity,
        @JsonProperty("unit_price") AcpMoney unitPrice,
        @JsonProperty("price_type") String priceType,
        @JsonProperty("recurring_period") @JsonInclude(JsonInclude.Include.NON_NULL) String recurringPeriod,
        @JsonProperty("due_now") AcpMoney dueNow,
        @JsonInclude(JsonInclude.Include.NON_NULL) JsonNode configuration,
        @JsonProperty("price_line") @JsonInclude(JsonInclude.Include.NON_NULL) JsonNode priceLine) {

    /** The offering the line is for, in the protocol's words. */
    @JsonPropertyOrder({"id", "title"})
    public record Item(String id, String title) {
    }

    public static LineItem plain(String id, Item item, int quantity, AcpMoney unitPrice, String priceType,
            String recurringPeriod, AcpMoney dueNow) {
        return new LineItem(id, item, quantity, unitPrice, priceType, recurringPeriod, dueNow, null, null);
    }

    public static LineItem configured(String id, Item item, int quantity, AcpMoney unitPrice, AcpMoney dueNow,
            JsonNode configuration, JsonNode priceLine) {
        return new LineItem(id, item, quantity, unitPrice, "recurring", "month", dueNow, configuration, priceLine);
    }
}
