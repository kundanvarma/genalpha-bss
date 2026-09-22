package com.bss.loyalty;

import com.bss.loyalty.dto.AdjustRequest;
import com.bss.loyalty.dto.LiabilityView;
import com.bss.loyalty.dto.LoyaltyMemberView;
import com.bss.loyalty.dto.LoyaltyProgramRequest;
import com.bss.loyalty.dto.LoyaltyProgramView;
import com.bss.loyalty.dto.LoyaltyTransactionView;
import com.bss.loyalty.dto.RedeemReceipt;
import com.bss.loyalty.dto.RedeemRequest;
import com.bss.loyalty.dto.Reward;
import com.bss.loyalty.dto.SweepReceipt;
import com.bss.loyalty.dto.TierVerdict;
import com.bss.loyalty.entity.LoyaltyMember;
import com.bss.loyalty.entity.LoyaltyProgram;
import com.bss.loyalty.entity.LoyaltyTransaction;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The wire records write the bytes the maps used to write: keys in the same
 * order, the program's rate at the scale it was stored, the three sweep
 * answers each with only their own keys. Pure Jackson, configured as Spring
 * Boot configures it — no context, no database.
 */
class DtoRoundTripTest {

    private final ObjectMapper json = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private static final OffsetDateTime T = OffsetDateTime.parse("2026-09-22T10:00:00Z");

    private String write(Object o) throws Exception {
        return json.writeValueAsString(o);
    }

    private static LoyaltyMember member() {
        LoyaltyMember m = new LoyaltyMember();
        m.setId("p1");
        m.setTenantId("genalpha");
        m.setBalance(3750);
        m.setTier("gold");
        m.setEnrolledAt(T);
        return m;
    }

    @Test
    void programView_keepsTheStoredScale_andTheMarker() throws Exception {
        LoyaltyProgram p = new LoyaltyProgram();
        p.setTenantId("genalpha");
        p.setEarnPointsPerCurrency(new BigDecimal("10.0"));
        p.setSilverThreshold(100);
        p.setGoldThreshold(300);
        assertEquals("{\"enabled\":true,\"earnPointsPerCurrency\":10.0,\"pointsPerGb\":100,\"expiryMonths\":0,"
                + "\"voucherPercent\":10,\"pointsPerVoucher\":200,\"silverThreshold\":100,\"goldThreshold\":300,"
                + "\"@type\":\"LoyaltyProgramSpecification\"}", write(LoyaltyProgramView.of(p)));
    }

    @Test
    void programRequest_readsNumbersAsStringsToo_andIgnoresStrangers() throws Exception {
        LoyaltyProgramRequest r = json.readValue(
                "{\"pointsPerGb\":\"100\",\"enabled\":\"false\",\"earnPointsPerCurrency\":1.5,\"foo\":1}",
                LoyaltyProgramRequest.class);
        assertEquals(100, r.pointsPerGb());
        assertEquals(Boolean.FALSE, r.enabled());
        assertEquals(new BigDecimal("1.5"), r.earnPointsPerCurrency());
        assertNull(r.goldThreshold());
    }

    @Test
    void memberView_andJournalRow() throws Exception {
        assertEquals("{\"id\":\"p1\",\"balance\":3750,\"tier\":\"gold\",\"enrolledAt\":\"2026-09-22T10:00:00Z\","
                + "\"@type\":\"LoyaltyProgramMember\"}", write(LoyaltyMemberView.of(member())));
        LoyaltyTransaction t = new LoyaltyTransaction();
        t.setTxType("burn");
        t.setPoints(-100);
        t.setCause("redeem:data:1GB:r1");
        t.setCreatedAt(T);
        assertEquals("{\"type\":\"burn\",\"points\":-100,\"cause\":\"redeem:data:1GB:r1\","
                + "\"createdAt\":\"2026-09-22T10:00:00Z\"}", write(LoyaltyTransactionView.of(t)));
    }

    @Test
    void redeemReceipt_isTheCardUnwrappedFirst_thenWhatThePointsBought() throws Exception {
        assertEquals("{\"id\":\"p1\",\"balance\":3750,\"tier\":\"gold\",\"enrolledAt\":\"2026-09-22T10:00:00Z\","
                + "\"@type\":\"LoyaltyProgramMember\",\"redeemed\":{\"gb\":1,\"points\":100,\"redemptionId\":\"r1\"}}",
                write(new RedeemReceipt(LoyaltyMemberView.of(member()), new Reward.Data(1, 100, "r1"))));
        assertEquals("{\"id\":\"p1\",\"balance\":3750,\"tier\":\"gold\",\"enrolledAt\":\"2026-09-22T10:00:00Z\","
                + "\"@type\":\"LoyaltyProgramMember\",\"redeemed\":{\"voucherCode\":\"LOYAL-ABCD1234\",\"percent\":10,\"points\":200}}",
                write(new RedeemReceipt(LoyaltyMemberView.of(member()), new Reward.Voucher("LOYAL-ABCD1234", 10, 200))));
    }

    @Test
    void redeemAndAdjustRequests_keepTheOldDefaults() throws Exception {
        RedeemRequest r = json.readValue("{\"type\":\"data\"}", RedeemRequest.class);
        assertEquals(1, r.gbOrDefault());
        assertEquals(3, json.readValue("{\"type\":\"data\",\"gb\":\"3\"}", RedeemRequest.class).gbOrDefault());
        AdjustRequest a = json.readValue("{\"partyId\":\"p1\",\"reason\":\"goodwill\"}", AdjustRequest.class);
        assertEquals(0, a.pointsOrZero());
        assertEquals(-5, json.readValue("{\"points\":\"-5\"}", AdjustRequest.class).pointsOrZero());
    }

    @Test
    void verdicts_andReceipts_writeTheirKeysInDeclarationOrder() throws Exception {
        assertEquals("{\"partyId\":\"p1\",\"tier\":\"none\"}", write(new TierVerdict("p1", "none")));
        assertEquals("{\"outstandingPoints\":3750,\"definition\":\"sum of all member balances — the operator's points liability\"}",
                write(LiabilityView.of(3750)));
        assertEquals("{\"skipped\":\"another replica sweeps\"}", write(SweepReceipt.Skipped.ANOTHER_REPLICA));
        assertEquals("{\"expired\":0,\"note\":\"no expiry configured\"}", write(SweepReceipt.NoExpiry.NONE));
        assertEquals("{\"expired\":40,\"members\":2}", write(new SweepReceipt.Swept(40, 2)));
    }
}
