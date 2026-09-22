package com.bss.intelligence.service;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** The draft offering an adopted advisor proposal became. */
@JsonPropertyOrder({"offeringId", "lifecycleStatus"})
public record AdoptReceipt(String offeringId, String lifecycleStatus) {
}
