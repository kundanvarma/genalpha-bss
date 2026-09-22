package com.bss.usage.dto;

/** The one-word answer of an internal door: {status}. */
public record Receipt(String status) {

    public static final Receipt ACCEPTED = new Receipt("accepted");
}
