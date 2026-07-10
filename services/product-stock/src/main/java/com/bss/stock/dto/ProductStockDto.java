package com.bss.stock.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ProductStockDto {

    @JsonProperty("id")
    private String id;

    @JsonProperty("href")
    private String href;

    @NotBlank(message = "name is required")
    @JsonProperty("name")
    private String name;

    @JsonProperty("productOffering")
    private Map<String, Object> productOffering;

    @NotNull(message = "stockedQuantity is required")
    @JsonProperty("stockedQuantity")
    private QuantityDto stockedQuantity;

    @JsonProperty("reservedQuantity")
    private QuantityDto reservedQuantity;

    @JsonProperty("availableQuantity")
    private QuantityDto availableQuantity;

    @JsonProperty("lastUpdate")
    private OffsetDateTime lastUpdate;

    @JsonProperty("@type")
    private String type = "ProductStock";

    public ProductStockDto() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getHref() {
        return href;
    }

    public void setHref(String href) {
        this.href = href;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Map<String, Object> getProductOffering() {
        return productOffering;
    }

    public void setProductOffering(Map<String, Object> productOffering) {
        this.productOffering = productOffering;
    }

    public QuantityDto getStockedQuantity() {
        return stockedQuantity;
    }

    public void setStockedQuantity(QuantityDto stockedQuantity) {
        this.stockedQuantity = stockedQuantity;
    }

    public QuantityDto getReservedQuantity() {
        return reservedQuantity;
    }

    public void setReservedQuantity(QuantityDto reservedQuantity) {
        this.reservedQuantity = reservedQuantity;
    }

    public QuantityDto getAvailableQuantity() {
        return availableQuantity;
    }

    public void setAvailableQuantity(QuantityDto availableQuantity) {
        this.availableQuantity = availableQuantity;
    }

    public OffsetDateTime getLastUpdate() {
        return lastUpdate;
    }

    public void setLastUpdate(OffsetDateTime lastUpdate) {
        this.lastUpdate = lastUpdate;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }
}
