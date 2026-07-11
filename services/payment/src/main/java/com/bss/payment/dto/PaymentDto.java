package com.bss.payment.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * TMF676 Payment. On create, paymentMethod may carry card details
 * (cardNumber, expiry, cvc) for the PSP — they are used for authorization and
 * never stored or echoed; responses carry only a masked label.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PaymentDto {

    @JsonProperty("id")
    private String id;

    @JsonProperty("href")
    private String href;

    @JsonProperty("description")
    private String description;

    @JsonProperty("status")
    private String status;

    @NotNull(message = "amount is required")
    @JsonProperty("amount")
    private MoneyDto amount;

    @JsonProperty("paymentMethod")
    private Map<String, Object> paymentMethod;

    @JsonProperty("authorizationCode")
    private String authorizationCode;

    @JsonProperty("settlementRef")
    private String settlementRef;

    @JsonProperty("pspProvider")
    private String pspProvider;

    @JsonProperty("correlatorId")
    private String correlatorId;

    @JsonProperty("relatedParty")
    private List<Map<String, Object>> relatedParty;

    @JsonProperty("paymentDate")
    private OffsetDateTime paymentDate;

    @JsonProperty("lastUpdate")
    private OffsetDateTime lastUpdate;

    @JsonProperty("@type")
    private String type = "Payment";

    public PaymentDto() {
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

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public MoneyDto getAmount() {
        return amount;
    }

    public void setAmount(MoneyDto amount) {
        this.amount = amount;
    }

    public Map<String, Object> getPaymentMethod() {
        return paymentMethod;
    }

    public void setPaymentMethod(Map<String, Object> paymentMethod) {
        this.paymentMethod = paymentMethod;
    }

    public String getAuthorizationCode() {
        return authorizationCode;
    }

    public void setAuthorizationCode(String authorizationCode) {
        this.authorizationCode = authorizationCode;
    }

    public String getSettlementRef() {
        return settlementRef;
    }

    public void setSettlementRef(String settlementRef) {
        this.settlementRef = settlementRef;
    }

    public String getPspProvider() {
        return pspProvider;
    }

    public void setPspProvider(String pspProvider) {
        this.pspProvider = pspProvider;
    }

    public String getCorrelatorId() {
        return correlatorId;
    }

    public void setCorrelatorId(String correlatorId) {
        this.correlatorId = correlatorId;
    }

    public List<Map<String, Object>> getRelatedParty() {
        return relatedParty;
    }

    public void setRelatedParty(List<Map<String, Object>> relatedParty) {
        this.relatedParty = relatedParty;
    }

    public OffsetDateTime getPaymentDate() {
        return paymentDate;
    }

    public void setPaymentDate(OffsetDateTime paymentDate) {
        this.paymentDate = paymentDate;
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
