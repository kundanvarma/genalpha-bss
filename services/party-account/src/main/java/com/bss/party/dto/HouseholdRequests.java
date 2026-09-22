package com.bss.party.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;

/** The small bodies of the household and billing-preference doors. */
public final class HouseholdRequests {

    private HouseholdRequests() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PayerRequest(String payerEmail) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record InviteRequest(String memberEmail) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RoleRequest(String role) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AllowanceRequest(BigDecimal monthlyValue) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BillDeliveryRequest(String preference) {
        /** 'default' (or nothing) resets to the operator's channel. */
        public String preferenceOrNull() {
            return preference == null || "default".equals(preference) ? null : preference;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BillingCycleRequest(Integer anchorDay) {
    }
}
