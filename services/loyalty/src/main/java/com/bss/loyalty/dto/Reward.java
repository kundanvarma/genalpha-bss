package com.bss.loyalty.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** What a redemption bought: gigabytes delivered to the meter, or a voucher minted as a promotion. */
public sealed interface Reward permits Reward.Data, Reward.Voucher {

    @JsonPropertyOrder({"gb", "points", "redemptionId"})
    record Data(int gb, long points, String redemptionId) implements Reward {
    }

    @JsonPropertyOrder({"voucherCode", "percent", "points"})
    record Voucher(String voucherCode, int percent, long points) implements Reward {
    }
}
