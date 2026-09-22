package com.bss.loyalty.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** The expiry sweep's three honest answers. */
public sealed interface SweepReceipt permits SweepReceipt.Skipped, SweepReceipt.NoExpiry, SweepReceipt.Swept {

    record Skipped(String skipped) implements SweepReceipt {
        public static final Skipped ANOTHER_REPLICA = new Skipped("another replica sweeps");
    }

    @JsonPropertyOrder({"expired", "note"})
    record NoExpiry(long expired, String note) implements SweepReceipt {
        public static final NoExpiry NONE = new NoExpiry(0, "no expiry configured");
    }

    @JsonPropertyOrder({"expired", "members"})
    record Swept(long expired, int members) implements SweepReceipt {
    }
}
