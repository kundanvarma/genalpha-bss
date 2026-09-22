package com.bss.userroles.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** What an erasure did to the identity: the login is gone, the id stays as the audit's reference. */
@JsonPropertyOrder({"category", "deleted", "retained", "note"})
public record EraseReceipt(String category, int deleted, int retained, String note) {
}
