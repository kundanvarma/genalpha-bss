package com.bss.billing.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;

/** The bodies a collection case accepts. A record cannot carry a field it does not declare. */
public final class CaseActionRequests {

    private CaseActionRequests() {
    }

    /** "I will pay by Friday": how many days (default the policy's max), how much (default all). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PromiseToPay(Integer days, BigDecimal amount) {
        public static final PromiseToPay EMPTY = new PromiseToPay(null, null);
    }

    /** A staff hold: type dispute (with an amount) or hardship. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Hold(String type, BigDecimal amount) {
    }

    /** The human decision at the ladder's end needs a reason. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record WriteOff(String reason) {
    }
}
