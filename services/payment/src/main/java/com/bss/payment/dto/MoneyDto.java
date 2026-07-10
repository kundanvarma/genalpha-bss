package com.bss.payment.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

/** TMF Money: a value with its currency unit. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MoneyDto {

    @JsonProperty("unit")
    private String unit;

    @JsonProperty("value")
    private BigDecimal value;

    public MoneyDto() {
    }

    public MoneyDto(String unit, BigDecimal value) {
        this.unit = unit;
        this.value = value;
    }

    public String getUnit() {
        return unit;
    }

    public void setUnit(String unit) {
        this.unit = unit;
    }

    public BigDecimal getValue() {
        return value;
    }

    public void setValue(BigDecimal value) {
        this.value = value;
    }
}
