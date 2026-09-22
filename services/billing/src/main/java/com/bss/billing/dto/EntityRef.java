package com.bss.billing.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** A TMF entity reference: an id, optionally a name and the referred type. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"id", "name", "@referredType", "@type"})
@JsonIgnoreProperties(ignoreUnknown = true)
public record EntityRef(String id, String name,
        @JsonProperty("@referredType") String referredType,
        @JsonProperty("@type") String type) {

    public static EntityRef of(String id) {
        return new EntityRef(id, null, null, null);
    }

    /** TMF678 requires a billingAccount reference on every bill. */
    public static EntityRef billingAccount(String id) {
        return new EntityRef(id, null, "BillingAccount", "BillingAccountRef");
    }
}
