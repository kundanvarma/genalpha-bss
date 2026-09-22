package com.bss.usage.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** TMF654 quantity on a bucket projection: {amount, units}; the OCS counts in doubles. */
@JsonPropertyOrder({"amount", "units"})
public record Amount(double amount, String units) {
}
