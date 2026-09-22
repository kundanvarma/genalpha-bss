package com.bss.devicecommerce.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;

/**
 * When an agreement becomes upgrade-eligible: after a paid share of the
 * principal ({@code paidSharePct}) or after a given instalment ({@code month}).
 * One key on the wire, both ways; the entity stores type + value.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"paidSharePct", "month"})
public record UpgradeRule(BigDecimal paidSharePct, BigDecimal month) {

    public static final String PAID_SHARE_PCT = "paidSharePct";
    public static final String MONTH = "month";

    /** The rule as the entity stores it: {@code paidSharePct} | {@code month}. */
    public static UpgradeRule of(String type, BigDecimal value) {
        if (type == null) {
            return null;
        }
        return PAID_SHARE_PCT.equals(type) ? new UpgradeRule(value, null) : new UpgradeRule(null, value);
    }

    /** Which rule the caller asked for; the paid-share rule wins when both are sent. */
    public String type() {
        return paidSharePct != null ? PAID_SHARE_PCT : month != null ? MONTH : null;
    }

    public BigDecimal value() {
        return paidSharePct != null ? paidSharePct : month;
    }
}
