package com.bss.stock.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;


/**
 * Body of the TMF687-style task operations. reserveProductStock names an
 * offering and quantity; releaseProductStock and consumeProductStock act on
 * everything the related order still holds.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StockOperationDto {

    @JsonProperty("productOffering")
    private EntityRef productOffering;

    @JsonProperty("quantity")
    private Integer quantity;

    @JsonProperty("relatedOrder")
    private EntityRef relatedOrder;

    /** TMF687 requestedProduct: the configured product (offering + productCharacteristic[] naming the variant). */
    @com.fasterxml.jackson.annotation.JsonProperty("requestedProduct")
    private ProductRef requestedProduct;

    @JsonProperty("state")
    private String state;

    public StockOperationDto() {
    }

    public EntityRef getProductOffering() {
        return productOffering;
    }

    public void setProductOffering(EntityRef productOffering) {
        this.productOffering = productOffering;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public void setQuantity(Integer quantity) {
        this.quantity = quantity;
    }

    public EntityRef getRelatedOrder() {
        return relatedOrder;
    }

    public void setRelatedOrder(EntityRef relatedOrder) {
        this.relatedOrder = relatedOrder;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public ProductRef getRequestedProduct() {
        return requestedProduct;
    }

    public void setRequestedProduct(ProductRef requestedProduct) {
        this.requestedProduct = requestedProduct;
    }
}
