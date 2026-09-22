package com.bss.catalog.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * What an accepted configuration costs: the monthly and one-time totals in
 * the tenant's money, every line that applied (house shape and v5 shape), what
 * leaving early would cost, and the deal engine's indicative view.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"monthlyTotal", "oneTimeTotal", "priceLine", "configurationPrice", "earlyTermination", "indicative", "@type"})
public record ConfigurationPriceSummary(Money monthlyTotal, Money oneTimeTotal, List<PriceLine> priceLine,
        List<ConfigurationPrice> configurationPrice, List<EarlyTermination> earlyTermination, IndicativePrice indicative,
        @JsonProperty("@type") String type) {

    /** One evaluated price: whose, which, the declared charge, per unit × units when it was multiplied, and the amount. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"offering", "name", "priceType", "price", "unitPrice", "quantity", "amount", "how", "appliesWhen"})
    public record PriceLine(EntityRef offering, String name, String priceType, Money price, BigDecimal unitPrice, Integer quantity,
            BigDecimal amount, String how, List<Map<String, Object>> appliesWhen) {
    }

    /** An early-termination fee: never charged on the configuration, reported so the channel can say it. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"name", "price", "declinesOver", "says"})
    public record EarlyTermination(String name, Money price, Quantity declinesOver, String says) {
    }
}
