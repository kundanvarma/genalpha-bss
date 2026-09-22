package com.bss.insight;

import com.bss.insight.dto.ActivationResult;
import com.bss.insight.dto.AudienceMember;
import com.bss.insight.dto.AudienceRequest;
import com.bss.insight.dto.AudienceView;
import com.bss.insight.dto.DecisionInput;
import com.bss.insight.dto.DecisionReceipt;
import com.bss.insight.dto.DecisionView;
import com.bss.insight.dto.DeskSuggestion;
import com.bss.insight.dto.Experience;
import com.bss.insight.dto.FrictionReport;
import com.bss.insight.dto.LeadCapture;
import com.bss.insight.dto.LeadForm;
import com.bss.insight.dto.PublishResult;
import com.bss.insight.dto.RefreshStatus;
import com.bss.insight.dto.SignalClassificationView;
import com.bss.insight.dto.SignalInput;
import com.bss.insight.dto.SignalView;
import com.bss.insight.dto.StitchReceipt;
import com.bss.insight.dto.SuggestedAction;
import com.bss.insight.dto.VisitorRequests;
import com.bss.insight.dto.VocSummary;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The wire records write the bytes the maps used to write: keys in the same
 * order, absent keys absent, null keys null where they were null, the
 * user-authored documents (a criteria tree, a preset's values, a decision's
 * context) passed through untouched. Pure Jackson, configured as Spring Boot
 * configures it — no context, no database.
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

    @Test
    void decisionRow_writesTheOutcomeBlockOnlyOnceAnOutcomeExists_andAllThreeKeysThen() throws Exception {
        DecisionView open = new DecisionView("d1", "ontology.recommend", "customer", "p1",
                json.readTree("[\"a\",\"b\"]"), json.readTree("[\"a\"]"), json.readTree("[]"), "a", null, "rules", "1",
                "why", json.readTree("{\"offeringId\":\"o1\"}"), json.readTree("{}"), "medium", false, "ontology", T,
                null, null, "Decision");
        assertEquals("{\"decisionId\":\"d1\",\"decisionPoint\":\"ontology.recommend\",\"subjectType\":\"customer\","
                + "\"subjectId\":\"p1\",\"candidates\":[\"a\",\"b\"],\"eligibleActions\":[\"a\"],\"constraints\":[],"
                + "\"action\":\"a\",\"propensity\":null,\"policy\":\"rules\",\"policyVersion\":\"1\",\"reason\":\"why\","
                + "\"context\":{\"offeringId\":\"o1\"},\"evidence\":{},\"autonomy\":\"medium\",\"fallback\":false,"
                + "\"source\":\"ontology\",\"decidedAt\":\"2026-09-22T10:00:00Z\",\"contract\":null,\"@type\":\"Decision\"}",
                write(open));
        DecisionView judged = new DecisionView("d1", "p", "s", "id", json.readTree("[]"), json.readTree("[]"),
                json.readTree("[]"), "a", new BigDecimal("0.3500"), "rules", "1", null, json.readTree("{}"),
                json.readTree("{}"), null, true, "campaign", T, "c-1",
                new DecisionView.Outcome("accepted", null, T), "Decision");
        String s = write(judged);
        assertTrue(s.contains("\"contract\":\"c-1\",\"outcome\":\"accepted\",\"outcomeValue\":null,"
                + "\"outcomeAt\":\"2026-09-22T10:00:00Z\",\"@type\":\"Decision\"}"), s);
        assertTrue(s.contains("\"propensity\":0.3500,"), s);
        // the receipt: the row re-labelled, then the sentences
        String r = write(new DecisionReceipt(judged.asReceipt(), List.of("Context used: none.")));
        assertTrue(r.startsWith("{\"decisionId\":\"d1\""), r);
        assertTrue(r.endsWith("\"@type\":\"DecisionReceipt\",\"receipt\":[\"Context used: none.\"]}"), r);
    }

    @Test
    void decisionInput_readsAPublishedDecision_keepingItsDocumentsVerbatim() throws Exception {
        DecisionInput d = json.readValue("{\"decisionId\":\"x\",\"decisionPoint\":\"p\",\"propensity\":1,"
                + "\"candidates\":[\"a\",{\"k\":1}],\"context\":{\"b\":2,\"a\":1},\"fallback\":true,\"extra\":\"ignored\"}",
                DecisionInput.class);
        assertEquals(1.0, d.propensity());
        assertEquals("[\"a\",{\"k\":1}]", d.candidates().toString());
        assertEquals("{\"b\":2,\"a\":1}", d.context().toString());
        assertTrue(d.fallback());
        assertNull(d.policy());
    }

    @Test
    void experience_isOnlyPersonalizedFalseWithoutConsent_andSpreadsTheRulesBlockBesideTheTypedKeys() throws Exception {
        assertEquals("{\"personalized\":false}", write(Experience.defaultPage()));
        Map<String, Object> open = new LinkedHashMap<>();
        open.put("teaserOfferingId", "off-1");
        Experience e = new Experience(true, List.of("mobile"), "social", null, "mobile", List.of("off-9"), "Hi", "Rule A", open);
        assertEquals("{\"personalized\":true,\"interests\":[\"mobile\"],\"channel\":\"social\",\"heroCategory\":\"mobile\","
                + "\"recentOfferings\":[\"off-9\"],\"banner\":\"Hi\",\"ruleName\":\"Rule A\",\"teaserOfferingId\":\"off-1\"}",
                write(e));
        assertEquals("{\"stitched\":false}", write(StitchReceipt.refused()));
        assertEquals("{\"stitched\":true,\"partyId\":\"p1\"}", write(StitchReceipt.to("p1")));
    }

    @Test
    void audienceMember_saysWhichPopulationItCameFromByItsKeys() throws Exception {
        assertEquals("{\"partyId\":\"p1\"}", write(AudienceMember.party("p1")));
        assertEquals("{\"partyId\":\"p1\",\"email\":\"a@b.c\"}", write(AudienceMember.party("p1").withEmail("a@b.c")));
        assertEquals("{\"visitorId\":\"v1\",\"partyId\":\"p1\"}", write(AudienceMember.visitor("v1", "p1")));
        assertEquals("{\"visitorId\":\"v1\"}", write(AudienceMember.visitor("v1", null)));
        assertEquals("{\"email\":\"\",\"prospectId\":\"x\",\"consent\":\"consented\"}",
                write(AudienceMember.prospect("x", "", "consented")));
    }

    @Test
    void audience_keepsTheMarketersTreeVerbatim_andReadsItAsObjectOrString() throws Exception {
        AudienceView v = new AudienceView("a1", "/insight/v1/audience/a1", "Gold", "customer", null, null,
                json.readTree("{\"all\":[{\"type\":\"trait\",\"key\":\"tier\",\"value\":\"gold\"}]}"), T, "Audience");
        assertEquals("{\"id\":\"a1\",\"href\":\"/insight/v1/audience/a1\",\"name\":\"Gold\",\"population\":\"customer\","
                + "\"criteria\":{\"all\":[{\"type\":\"trait\",\"key\":\"tier\",\"value\":\"gold\"}]},"
                + "\"lastUpdate\":\"2026-09-22T10:00:00Z\",\"@type\":\"Audience\"}", write(v));
        AudienceRequest asObject = json.readValue("{\"name\":\"n\",\"criteria\":{\"type\":\"trait\"}}", AudienceRequest.class);
        assertTrue(asObject.hasCriteria());
        assertTrue(asObject.criteria().isObject());
        AudienceRequest asText = json.readValue("{\"name\":\"n\",\"criteria\":\"{\\\"type\\\":\\\"trait\\\"}\"}", AudienceRequest.class);
        assertTrue(asText.criteria().isTextual());
        AudienceRequest nulled = json.readValue("{\"name\":\"n\",\"criteria\":null}", AudienceRequest.class);
        assertFalse(nulled.hasCriteria());
    }

    @Test
    void deskSuggestion_writesTheSentenceThenTheFacts_andTheActionByKind() throws Exception {
        DeskSuggestion base = new DeskSuggestion("preset-abc", "preset", "bundles", "Save a preset", "3 submissions",
                "operator", SuggestedAction.Preset.of("console", "bundles", Map.of("type", "fibre")), false,
                "desk-preset-abc", null, null, null, null, null, null, null, null);
        assertEquals("{\"id\":\"preset-abc\",\"kind\":\"preset\",\"target\":\"bundles\",\"title\":\"Save a preset\","
                + "\"evidence\":\"3 submissions\",\"audience\":\"operator\",\"action\":{\"kind\":\"preset\",\"desk\":\"console\","
                + "\"form\":\"bundles\",\"values\":{\"type\":\"fibre\"}},\"quiet\":false,\"decisionId\":\"desk-preset-abc\","
                + "\"form\":\"bundles\",\"fields\":[\"type\"],\"count\":3}", write(base.preset("bundles", List.of("type"), 3)));
        DeskSuggestion holdout = new DeskSuggestion("holdout-1", "holdout", "j1", "Add a holdout", "no control group", "operator",
                SuggestedAction.Http.of("PATCH", "/tmf-api/campaignManagement/v4/journey/j1",
                        json.createObjectNode().put("holdoutPercent", 10)), true, "desk-holdout-1",
                null, null, null, null, null, null, null, null);
        assertTrue(write(holdout).contains("\"action\":{\"kind\":\"action\",\"method\":\"PATCH\","
                + "\"path\":\"/tmf-api/campaignManagement/v4/journey/j1\",\"body\":{\"holdoutPercent\":10}},\"quiet\":true"));
        assertEquals("{\"form\":\"orders\",\"count\":2}", write(new FrictionReport.AbandonedForm("orders", 2, null)));
        assertEquals("{\"form\":\"orders\",\"count\":2,\"stopField\":\"msisdn\"}",
                write(new FrictionReport.AbandonedForm("orders", 2, "msisdn")));
    }

    @Test
    void signal_writesDuplicateOnlyWhenTrue_andTheClassificationAfterTheType() throws Exception {
        SignalView v = new SignalView("s1", "ticket", "t-1", null, "support", null, "text [NAME]", "text Håkon",
                json.readTree("{\"severity\":\"high\"}"), json.readTree("{\"NAME\":1}"), T, null, "CustomerSignal", null);
        assertEquals("{\"id\":\"s1\",\"source\":\"ticket\",\"sourceRef\":\"t-1\",\"channel\":\"support\",\"text\":\"text [NAME]\","
                + "\"twin\":\"text Håkon\",\"context\":{\"severity\":\"high\"},\"redactions\":{\"NAME\":1},"
                + "\"receivedAt\":\"2026-09-22T10:00:00Z\",\"@type\":\"CustomerSignal\"}", write(v));
        SignalClassificationView c = new SignalClassificationView("negative", "billing", null, "double charge", 3, null,
                true, "price", json.readTree("{\"sentiment\":\"text\"}"), "stub", "m", T, "SignalClassification");
        String withC = write(v.withClassification(c));
        assertTrue(withC.endsWith("\"@type\":\"CustomerSignal\",\"classification\":{\"sentiment\":\"negative\","
                + "\"aspect\":\"billing\",\"painPoint\":\"double charge\",\"painImpact\":3,\"churnSignal\":true,"
                + "\"churnReason\":\"price\",\"evidence\":{\"sentiment\":\"text\"},\"provider\":\"stub\",\"model\":\"m\","
                + "\"classifiedAt\":\"2026-09-22T10:00:00Z\",\"@type\":\"SignalClassification\"}}"), withC);
        SignalView dup = new SignalView("s1", "ticket", null, null, null, null, "t", null, null, null, T, true, "CustomerSignal", null);
        assertTrue(write(dup).contains("\"receivedAt\":\"2026-09-22T10:00:00Z\",\"duplicate\":true,\"@type\""));
        SignalInput in = json.readValue("{\"source\":\"chat\",\"text\":\"hi\",\"context\":{\"a\":1},\"unknown\":true}", SignalInput.class);
        assertEquals("{\"a\":1}", in.context().toString());
        assertEquals("mention", in.withSource("mention").source());
    }

    @Test
    void twoHonestAnswers_areSealedVariants_notMapsWithDifferentKeys() throws Exception {
        assertEquals("{\"status\":\"not_found\"}", write(LeadCapture.NotFound.page()));
        assertEquals("{\"status\":\"declined\",\"captured\":false,\"reason\":\"consent is required to capture a lead\"}",
                write(LeadCapture.Rejected.declined()));
        assertEquals("{\"status\":\"captured\",\"captured\":true,\"source\":\"spring\"}", write(LeadCapture.Captured.from("spring")));
        assertEquals("{\"published\":false,\"reason\":\"no handle\"}", write(PublishResult.NotPublished.because("no handle")));
        assertEquals("{\"published\":true,\"id\":\"p1\",\"permalink\":\"https://x/p1\",\"provider\":\"meta\"}",
                write(PublishResult.Published.of("p1", "https://x/p1", "meta")));
        assertEquals("{\"activated\":false,\"reason\":\"wall\"}", write(new ActivationResult.Refused(false, "wall")));
        assertEquals("{\"jobId\":\"j\",\"mode\":\"seed\",\"destination\":\"meta\",\"status\":\"queued\",\"enabled\":true}",
                write(new ActivationResult.Queued("j", "seed", "meta", "queued", true)));
    }

    @Test
    void requests_coerceWhatABrowserSends_andIgnoreWhatTheyDoNotDeclare() throws Exception {
        LeadForm ticked = json.readValue("{\"email\":\"a@b.c\",\"consent\":true,\"extra\":1}", LeadForm.class);
        assertTrue(ticked.consented());
        assertTrue(json.readValue("{\"consent\":\"on\"}", LeadForm.class).consented());
        assertFalse(json.readValue("{\"consent\":\"no\"}", LeadForm.class).consented());
        VisitorRequests.Consent c = json.readValue("{\"visitorId\":\"v\",\"analytics\":true,\"personalization\":false,\"tenantId\":\"x\"}",
                VisitorRequests.Consent.class);
        assertEquals("v", c.visitorId());
        assertTrue(c.analytics());
    }

    @Test
    void runReceipt_unwrapsTheStatusFirst_thenAddsRefreshed() throws Exception {
        RefreshStatus s = new RefreshStatus(true, 300000, 25, 1, 2, 0, null, 12, 2, 100, 512);
        assertEquals("{\"enabled\":true,\"intervalMs\":300000,\"maxPerRun\":25,\"totalRuns\":1,\"totalRefreshed\":2,"
                + "\"totalErrors\":0,\"lastRunAt\":null,\"lastDurationMs\":12,\"lastRefreshed\":2,\"heapUsedMb\":100,"
                + "\"heapMaxMb\":512,\"refreshed\":3}", write(new RefreshStatus.RunReceipt(s, 3)));
    }

    @Test
    void vocAspect_keepsCounterOrder_andAPainPointLeavesImpactOffWhenUnknown() throws Exception {
        VocSummary.Aspect a = new VocSummary.Aspect("billing", 4, 1, 1, 2, 3, 2,
                List.of(new VocSummary.PainPoint("double charge", null, "s1")), 0.0, false);
        assertEquals("{\"aspect\":\"billing\",\"total\":4,\"positive\":1,\"neutral\":1,\"negative\":2,\"thisWeek\":3,"
                + "\"weekNegatives\":2,\"painPoints\":[{\"painPoint\":\"double charge\",\"signalId\":\"s1\"}],"
                + "\"baselineWeeklyNegatives\":0.0,\"deviating\":false}", write(a));
    }
}
