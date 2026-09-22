package com.bss.policy;

import com.bss.policy.dto.DecisionRequest;
import com.bss.policy.dto.DecisionView;
import com.bss.policy.dto.ExperienceView;
import com.bss.policy.dto.PolicyRulePatch;
import com.bss.policy.dto.PolicyRuleRequest;
import com.bss.policy.dto.PolicyRuleView;
import com.bss.policy.dto.PriceResult;
import com.bss.policy.dto.Teaser;
import com.bss.policy.entity.PolicyRule;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The wire records write the bytes the maps used to write: a rule with its
 * nulls in place and its experience document as authored, a decision in its
 * two shapes, money at the stored scale. Pure Jackson, configured as Spring
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

    private static PolicyRule rule() {
        PolicyRule r = new PolicyRule();
        r.setId("r1");
        r.setHref("/tmf-api/policyManagement/v4/policyRule/r1");
        r.setName("Snap");
        r.setDomain("pricing");
        r.setEffect("adjust");
        r.setPriority(5);
        r.setEnabled(true);
        r.setCondition("{\"var\":\"x\"}");
        r.setAdjustmentType("percent");
        r.setAdjustmentValue(new BigDecimal("-10.0000"));
        r.setLastUpdate(T);
        return r;
    }

    @Test
    void ruleView_writesNullsInPlace_andTheExperienceOnlyWhenPresent() throws Exception {
        assertEquals("{\"id\":\"r1\",\"href\":\"/tmf-api/policyManagement/v4/policyRule/r1\",\"name\":\"Snap\","
                + "\"description\":null,\"domain\":\"pricing\",\"effect\":\"adjust\",\"priority\":5,\"enabled\":true,"
                + "\"condition\":\"{\\\"var\\\":\\\"x\\\"}\",\"message\":null,\"adjustmentType\":\"percent\","
                + "\"adjustmentValue\":-10.0000,\"lastUpdate\":\"2026-09-22T10:00:00Z\",\"@type\":\"PolicyRule\"}",
                write(PolicyRuleView.of(rule(), null)));
        String withDoc = write(PolicyRuleView.of(rule(), json.readTree("{\"hero\":\"snap\",\"tiles\":[1,2]}")));
        assertTrue(withDoc.contains("\"adjustmentValue\":-10.0000,\"experience\":{\"hero\":\"snap\",\"tiles\":[1,2]},\"lastUpdate\""));
        String withText = write(PolicyRuleView.of(rule(), json.readTree("\"plain words\"")));
        assertTrue(withText.contains("\"experience\":\"plain words\""));
    }

    @Test
    void ruleRequest_readsTheAuthoredDocuments_asTrees() throws Exception {
        PolicyRuleRequest r = json.readValue("{\"name\":\"Snap\",\"condition\":\"{\\\"var\\\":\\\"x\\\"}\",\"priority\":\"7\","
                + "\"enabled\":\"true\",\"adjustmentValue\":-10,\"experience\":{\"a\":1},\"stranger\":1}", PolicyRuleRequest.class);
        assertTrue(r.condition().isTextual());
        assertEquals(7, r.priority());
        assertEquals(Boolean.TRUE, r.enabled());
        assertTrue(r.adjustmentValue().isNumber());
        assertTrue(r.experience().isObject());
        PolicyRuleRequest obj = json.readValue("{\"name\":\"Snap\",\"condition\":{\"var\":\"x\"}}", PolicyRuleRequest.class);
        assertEquals("{\"var\":\"x\"}", obj.condition().toString());
        assertNull(obj.priority());
    }

    @Test
    void rulePatch_tellsAbsentFromNull() throws Exception {
        PolicyRulePatch p = json.readValue("{\"description\":null,\"priority\":\"9\",\"foo\":1}", PolicyRulePatch.class);
        assertNull(p.name());
        assertTrue(p.description().isNull());
        assertEquals("9", p.priority().asText());
    }

    @Test
    void decision_hasTwoShapes() throws Exception {
        assertEquals("{\"decision\":\"allow\"}", write(DecisionView.Allowed.INSTANCE));
        assertEquals("{\"decision\":\"deny\",\"ruleId\":\"r1\",\"ruleName\":\"Snap\",\"message\":\"No\"}",
                write(new DecisionView.ByRule("deny", "r1", "Snap", "No")));
        assertEquals("{\"decision\":\"allow\",\"ruleId\":\"r1\",\"ruleName\":\"Snap\",\"message\":null}",
                write(new DecisionView.ByRule("allow", "r1", "Snap", null)));
        DecisionRequest q = json.readValue("{\"domain\":\"launch\",\"context\":{\"snap\":\"1\"},\"x\":2}", DecisionRequest.class);
        assertEquals("launch", q.domain());
        assertTrue(q.context().isObject());
        assertTrue(json.readValue("{\"context\":\"x\"}", DecisionRequest.class).context().isTextual());
    }

    @Test
    void experience_isEmptyWhenNoRuleHasAnOpinion() throws Exception {
        assertEquals("{}", write(ExperienceView.NONE));
        assertEquals("{\"ruleId\":\"r1\",\"ruleName\":\"Snap\",\"banner\":\"Hi\",\"experience\":{\"hero\":\"x\"}}",
                write(new ExperienceView("r1", "Snap", "Hi", json.readTree("{\"hero\":\"x\"}"))));
        assertEquals("{\"ruleId\":\"r1\",\"ruleName\":\"Snap\"}", write(new ExperienceView("r1", "Snap", null, null)));
    }

    @Test
    void price_keepsScales_andTheIndicativeLabelIsACopy() throws Exception {
        PriceResult p = PriceResult.of(BigDecimal.valueOf(100.0), List.of(
                new PriceResult.Adjustment("r1", "Snap", "Snap deal", "percent", new BigDecimal("-10.0000"),
                        new BigDecimal("-10.00"))), new BigDecimal("90.00"));
        assertEquals("{\"basePrice\":100.0,\"adjustments\":[{\"ruleId\":\"r1\",\"ruleName\":\"Snap\",\"label\":\"Snap deal\","
                + "\"type\":\"percent\",\"value\":-10.0000,\"amount\":-10.00}],\"total\":90.00}", write(p));
        assertTrue(write(p.asIndicative()).endsWith("\"total\":90.00,\"indicative\":true}"));
        assertEquals("{\"basePrice\":0.0,\"adjustments\":[],\"total\":0.00}",
                write(PriceResult.of(BigDecimal.valueOf(0.0), List.of(), new BigDecimal("0.00"))));
    }

    @Test
    void teaser_writesThePublicFaceOnly() throws Exception {
        assertEquals("{\"name\":\"Snap\",\"message\":\"Snap deal\",\"audience\":\"consumer\",\"adjustmentType\":\"percent\","
                + "\"adjustmentValue\":-10.0000,\"relatedOfferingIds\":[\"o2\"]}",
                write(new Teaser("Snap", "Snap deal", "consumer", "percent", new BigDecimal("-10.0000"), List.of("o2"))));
    }
}
