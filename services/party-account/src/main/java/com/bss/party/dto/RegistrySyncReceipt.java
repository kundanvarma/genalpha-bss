package com.bss.party.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** One feed poll: how many events were applied and where the cursor now stands. */
@JsonPropertyOrder({"processed", "lastSeq"})
public record RegistrySyncReceipt(int processed, long lastSeq) {
}
