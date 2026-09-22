package com.bss.cart.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.List;

/**
 * An ACP checkout session as the agent sees it: the priced lines, the totals
 * the token will be charged, the buyer it named, and — once completed — the
 * order it became. The buyer block is the agent's own document, stored and
 * answered verbatim.
 */
@JsonPropertyOrder({"id", "status", "currency", "line_items", "totals", "buyer", "order"})
public record CheckoutSessionView(String id, String status, String currency,
        @JsonProperty("line_items") List<LineItem> lineItems,
        List<Total> totals,
        @JsonInclude(JsonInclude.Include.NON_NULL) JsonNode buyer,
        @JsonInclude(JsonInclude.Include.NON_NULL) OrderRef order) {

    @JsonPropertyOrder({"type", "amount", "currency"})
    public record Total(String type, String amount, String currency) {
    }

    @JsonPropertyOrder({"id", "checkout_session_id", "permalink_url"})
    public record OrderRef(String id, @JsonProperty("checkout_session_id") String checkoutSessionId,
            @JsonProperty("permalink_url") String permalinkUrl) {
    }

    /** The two totals the protocol asks for; here they coincide, because what is due now IS the total. */
    public static List<Total> totalsOf(BigDecimal dueNow, String currency) {
        String amount = dueNow.toPlainString();
        return List.of(new Total("items_due_now", amount, currency), new Total("total", amount, currency));
    }
}
