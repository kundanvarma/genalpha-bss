package com.bss.devicecommerce.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.List;

/**
 * Sign a device financing agreement. The total cost of ownership is
 * deliberately NOT derived: the channel must have shown it. Tenant, owner
 * (for a customer token) and state come from the token and the store.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DeviceAgreementRequest(
        BigDecimal principal,
        Integer termMonths,
        String financingModel,
        BigDecimal totalCostOfOwnership,
        List<RelatedPartyRef> relatedParty,
        String subscriptionRef,
        String orderRef,
        String deviceRef,
        String imei,
        String serialNo,
        BigDecimal monthlyAmount,
        String financierRef,
        String externalAgreementNo,
        String titleHolder,
        UpgradeRule upgradeRule,
        BigDecimal residualValue,
        BigDecimal subsidyAmount,
        BigDecimal shippingCost,
        String currency,
        String paymentRef) {

    /** The first related party's id, as the map path read it. */
    public String relatedPartyId() {
        return relatedParty == null || relatedParty.isEmpty() || relatedParty.get(0) == null
                ? null : relatedParty.get(0).id();
    }
}
