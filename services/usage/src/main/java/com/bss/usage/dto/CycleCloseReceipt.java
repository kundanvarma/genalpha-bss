package com.bss.usage.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** POST /cycleClose: the period closed and how many buckets rolled. */
@JsonPropertyOrder({"period", "rolledBuckets"})
public record CycleCloseReceipt(String period, int rolledBuckets) {
}
