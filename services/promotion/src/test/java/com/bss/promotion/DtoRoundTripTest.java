package com.bss.promotion;

import com.bss.promotion.dto.CheckPromotionRequest;
import com.bss.promotion.dto.PromotionCheck;
import com.bss.promotion.dto.PromotionPatch;
import com.bss.promotion.dto.PromotionRedemptionView;
import com.bss.promotion.dto.PromotionRequest;
import com.bss.promotion.dto.PromotionView;
import com.bss.promotion.dto.RedeemRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure Jackson: the bytes, the key order and the money scale of promotion. */
class DtoRoundTripTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private String write(Object o) throws Exception {
        return mapper.writeValueAsString(o);
    }

    private static final OffsetDateTime CLOCK = OffsetDateTime.parse("2026-09-23T08:00:00Z");

    @Test
    void aPromotionLeavesOffWhatTheMapLeftOff() throws Exception {
        assertThat(write(PromotionView.of("p-1", "/tmf-api/promotionManagement/v4/promotion/p-1",
                "Welcome 10", null, "WELCOME10", "Active", new BigDecimal("10.00"), null,
                List.of(), CLOCK)))
                .isEqualTo("{\"id\":\"p-1\",\"href\":\"/tmf-api/promotionManagement/v4/promotion/"
                        + "p-1\",\"name\":\"Welcome 10\",\"code\":\"WELCOME10\","
                        + "\"lifecycleStatus\":\"Active\",\"percentage\":10.00,\"appliesTo\":[],"
                        + "\"lastUpdate\":\"2026-09-23T08:00:00Z\",\"@type\":\"Promotion\"}");
        assertThat(write(PromotionView.of("p-1", "/h", "Welcome 10", "ten percent off",
                "WELCOME10", "Active", new BigDecimal("12.5"), 3, List.of("off-1"), CLOCK)))
                .contains("\"name\":\"Welcome 10\",\"description\":\"ten percent off\",\"code\"")
                .contains("\"percentage\":12.5,\"durationMonths\":3,\"appliesTo\":[\"off-1\"]");
    }

    @Test
    void theStoredScaleIsTheScaleOnTheWire() throws Exception {
        // the create echo answers at the caller's scale, a re-read at the column's
        assertThat(write(PromotionView.of("p-1", "/h", "n", null, "C", "Active",
                new BigDecimal("12.5"), null, List.of(), CLOCK))).contains("\"percentage\":12.5,");
        assertThat(write(PromotionView.of("p-1", "/h", "n", null, "C", "Active",
                new BigDecimal("12.50"), null, List.of(), CLOCK))).contains("\"percentage\":12.50,");
    }

    @Test
    void aRedemptionCarriesTheMonthsOnlyWhenThePromotionHadADuration() throws Exception {
        assertThat(write(PromotionRedemptionView.of("r-1", "p-1", "Welcome 10", "WELCOME10",
                "party-1", new BigDecimal("10.00"), List.of("off-1"), 3)))
                .isEqualTo("{\"id\":\"r-1\",\"promotionId\":\"p-1\",\"name\":\"Welcome 10\","
                        + "\"code\":\"WELCOME10\",\"relatedPartyId\":\"party-1\","
                        + "\"percentage\":10.00,\"appliesTo\":[\"off-1\"],\"monthsLeft\":3,"
                        + "\"@type\":\"PromotionRedemption\"}");
        assertThat(write(PromotionRedemptionView.of("r-1", "p-1", "n", "C", "party-1",
                new BigDecimal("10.00"), List.of(), null)))
                .endsWith("\"appliesTo\":[],\"@type\":\"PromotionRedemption\"}");
    }

    @Test
    void aBadCodeLearnsNothingButThatItIsBad() throws Exception {
        assertThat(write(PromotionCheck.Invalid.INSTANCE)).isEqualTo("{\"valid\":false}");
        assertThat(write(PromotionCheck.Valid.of("Welcome 10", new BigDecimal("12.50"), 3,
                List.of("off-1", "off-2"))))
                .isEqualTo("{\"valid\":true,\"name\":\"Welcome 10\",\"percentage\":12.50,"
                        + "\"durationMonths\":3,\"appliesTo\":[\"off-1\",\"off-2\"]}");
        assertThat(write(PromotionCheck.Valid.of("Welcome 10", new BigDecimal("10.00"), null,
                List.of())))
                .isEqualTo("{\"valid\":true,\"name\":\"Welcome 10\",\"percentage\":10.00,"
                        + "\"appliesTo\":[]}");
    }

    /* ---------- the leniency the map path had ---------- */

    @Test
    void aPercentageKeepsTheCallersOwnText() throws Exception {
        assertThat(new BigDecimal(mapper.readValue("{\"percentage\":10}", PromotionRequest.class)
                .percentage().asText())).isEqualTo(new BigDecimal("10"));
        assertThat(new BigDecimal(mapper.readValue("{\"percentage\":12.5}", PromotionRequest.class)
                .percentage().asText())).isEqualTo(new BigDecimal("12.5"));
        assertThat(new BigDecimal(mapper.readValue("{\"percentage\":\"12.50\"}",
                PromotionRequest.class).percentage().asText())).isEqualTo(new BigDecimal("12.50"));
    }

    @Test
    void onlyARealJsonNumberSetsTheDuration() throws Exception {
        assertThat(mapper.readValue("{\"durationMonths\":3}", PromotionRequest.class)
                .durationMonths().isNumber()).isTrue();
        // the map path tested `instanceof Number`: a posted "3" was ignored, and still is
        assertThat(mapper.readValue("{\"durationMonths\":\"3\"}", PromotionRequest.class)
                .durationMonths().isNumber()).isFalse();
    }

    @Test
    void anExplicitNullIsTheSameAsAnAbsentKey() throws Exception {
        assertThat(PromotionRequest.absent(
                mapper.readValue("{\"percentage\":null}", PromotionRequest.class).percentage()))
                .isTrue();
        assertThat(PromotionRequest.absent(
                mapper.readValue("{}", PromotionRequest.class).percentage())).isTrue();
        assertThat(PromotionRequest.absent(
                mapper.readValue("{\"percentage\":0}", PromotionRequest.class).percentage()))
                .isFalse();
        PromotionPatch patch = mapper.readValue("{\"percentage\":null,\"lifecycleStatus\":null}",
                PromotionPatch.class);
        assertThat(PromotionRequest.absent(patch.percentage())).isTrue();
        assertThat(patch.lifecycleStatus()).isNull();
    }

    @Test
    void theOpenBlocksArriveAsTheTreesTheyWereWrittenAs() throws Exception {
        PromotionRequest req = mapper.readValue(
                "{\"name\":\"Welcome\",\"code\":\"W10\",\"percentage\":10,"
                        + "\"appliesTo\":[\"off-1\"],"
                        + "\"validFor\":{\"startDateTime\":\"2026-01-01T00:00:00Z\"},"
                        + "\"unknown\":1}", PromotionRequest.class);
        assertThat(write(req.appliesTo())).isEqualTo("[\"off-1\"]");
        assertThat(req.validFor().get("startDateTime").asText()).isEqualTo("2026-01-01T00:00:00Z");
        assertThat(req.appliesTo().isArray()).isTrue();
        assertThat(mapper.readValue("{\"appliesTo\":[]}", PromotionRequest.class)
                .appliesTo().isEmpty()).isTrue();
    }

    @Test
    void theTwoSmallBodiesTakeExactlyTheirTwoFields() throws Exception {
        assertThat(mapper.readValue("{\"code\":\"W10\",\"other\":1}", CheckPromotionRequest.class)
                .code()).isEqualTo("W10");
        RedeemRequest redeem = mapper.readValue(
                "{\"code\":\"W10\",\"relatedPartyId\":\"party-1\",\"tenantId\":\"other\"}",
                RedeemRequest.class);
        assertThat(redeem.relatedPartyId()).isEqualTo("party-1");
        assertThat(mapper.readValue("{}", RedeemRequest.class).code()).isNull();
    }
}
