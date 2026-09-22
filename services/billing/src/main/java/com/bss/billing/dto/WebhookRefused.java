package com.bss.billing.dto;

/** A bank or distribution partner knocked with a credential nobody recognises. */
public record WebhookRefused(String error) {
}
