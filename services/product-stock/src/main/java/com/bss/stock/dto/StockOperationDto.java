package com.bss.stock.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * Body of the TMF687-style task operations. reserveProductStock names an
 * offering and quantity; releaseProductStock and consumeProductStock act on
 * everything the related order still holds.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StockOperationDto {

    @JsonProperty("productOffering")
    private Map<String, Object> productOffering;

    @JsonProperty("quantity")
    private Integer quantity;

    @JsonProperty("relatedOrder")
    private Map<String, Object> relatedOrder;

    @JsonProperty("state")
    private String state;

    public StockOperationDto() {
    }

    public Map<String, Object> getProductOffering() {
        return productOffering;
    }

    public void setProductOffering(Map<String, Object> productOffering) {
        this.productOffering = productOffering;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public void setQuantity(Integer quantity) {
        this.quantity = quantity;
    }

    public Map<String, Object> getRelatedOrder() {
        return relatedOrder;
    }

    public void setRelatedOrder(Map<String, Object> relatedOrder) {
        this.relatedOrder = relatedOrder;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }
}
