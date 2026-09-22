package com.bss.quote.dto;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One line of a quote — also the shape stored in the quote's {@code items}
 * JSON column. An intent-born line carries the offering, the OSS's reason,
 * the catalog price and the token allowance; a CPQ line carries the
 * quantity, the priced unit (list price and the discount that won) and
 * whether it recurs. Keys the store holds that this record does not name
 * round-trip through {@code extensions}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"offering", "reason", "quantity", "unitPrice", "allowance", "listUnitPrice",
        "segmentDiscountPercent", "volumeDiscountPercent", "pricedBy", "recurring"})
public record QuoteItem(EntityRef offering, String reason, Integer quantity, Money unitPrice,
        Allowance allowance, BigDecimal listUnitPrice, BigDecimal segmentDiscountPercent,
        BigDecimal volumeDiscountPercent, String pricedBy, Boolean recurring,
        @JsonAnyGetter @JsonAnySetter Map<String, Object> extensions) {

    public QuoteItem {
        extensions = extensions == null ? new LinkedHashMap<>() : extensions;
    }

    /** An intent-born line: what the OSS proposed, priced from the catalog. */
    public static QuoteItem proposed(EntityRef offering, String reason, Money unitPrice, Allowance allowance) {
        return new QuoteItem(offering, reason, null, unitPrice, allowance, null, null, null, null, null, null);
    }

    /** A CPQ line at list price. */
    public static QuoteItem priced(EntityRef offering, int quantity, Money unitPrice, boolean recurring) {
        return new QuoteItem(offering, null, quantity, unitPrice, null, null, null, null, null, recurring, null);
    }

    /** A CPQ line where a segment price won. */
    public static QuoteItem bySegment(EntityRef offering, int quantity, Money unitPrice, BigDecimal listUnitPrice,
            BigDecimal discountPercent, String segment, boolean recurring) {
        return new QuoteItem(offering, null, quantity, unitPrice, null, listUnitPrice, discountPercent, null,
                "segment:" + segment, recurring, null);
    }

    /** A CPQ line where a volume tier won. */
    public static QuoteItem byVolume(EntityRef offering, int quantity, Money unitPrice, BigDecimal listUnitPrice,
            BigDecimal discountPercent, boolean recurring) {
        return new QuoteItem(offering, null, quantity, unitPrice, null, listUnitPrice, null, discountPercent,
                "volume", recurring, null);
    }

    /**
     * Token economics on the line: what is included and what overage costs,
     * as the usage component describes them (its documents, not re-shaped).
     */
    @JsonPropertyOrder({"usageType", "included", "overagePrice"})
    public record Allowance(String usageType, JsonNode included, JsonNode overagePrice) {
    }
}
