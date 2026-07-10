package com.bss.stock.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/** TMF Quantity: an amount with its units (e.g. 25 "unit"). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class QuantityDto {

    @JsonProperty("amount")
    private Integer amount;

    @JsonProperty("units")
    private String units;

    public QuantityDto() {
    }

    public QuantityDto(Integer amount, String units) {
        this.amount = amount;
        this.units = units;
    }

    public Integer getAmount() {
        return amount;
    }

    public void setAmount(Integer amount) {
        this.amount = amount;
    }

    public String getUnits() {
        return units;
    }

    public void setUnits(String units) {
        this.units = units;
    }
}
