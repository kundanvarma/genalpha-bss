package com.bss.campaign;

import com.bss.campaign.decision.DecisionRecord;
import com.bss.campaign.dto.ArbitrationDecisionView;
import com.bss.campaign.dto.ArmRow;
import com.bss.campaign.dto.ArmSpec;
import com.bss.campaign.dto.AttributionReport;
import com.bss.campaign.dto.AudienceSyncResult;
import com.bss.campaign.dto.CampaignExecutionView;
import com.bss.campaign.dto.CampaignRequest;
import com.bss.campaign.dto.CampaignStats;
import com.bss.campaign.dto.CampaignView;
import com.bss.campaign.dto.ClubLinkReceipt;
import com.bss.campaign.dto.CommunityGoalView;
import com.bss.campaign.dto.ContractView;
import com.bss.campaign.dto.ConversionReceipt;
import com.bss.campaign.dto.Conversions;
import com.bss.campaign.dto.DecisionDryRun;
import com.bss.campaign.dto.DecisionPointView;
import com.bss.campaign.dto.DryRunRequest;
import com.bss.campaign.dto.EnrollmentReceipt;
import com.bss.campaign.dto.EnrollmentRequest;
import com.bss.campaign.dto.EraseReceipt;
import com.bss.campaign.dto.ExecutionReceipt;
import com.bss.campaign.dto.JourneyRequest;
import com.bss.campaign.dto.JourneyStats;
import com.bss.campaign.dto.JourneyView;
import com.bss.campaign.dto.LearningContractRequest;
import com.bss.campaign.dto.LearningContractView;
import com.bss.campaign.dto.MartechSettingsView;
import com.bss.campaign.dto.PrivacyExport;
import com.bss.campaign.dto.RedeemReceipt;
import com.bss.campaign.dto.ReferralCodeView;
import com.bss.campaign.dto.ReferralReport;
import com.bss.campaign.dto.SegmentEnrollmentReceipt;
import com.bss.campaign.dto.TuneEntry;
import com.bss.campaign.dto.TuneResult;
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
 * author's documents (steps, arms, a decision's context) passed through as
 * written, money at the scale it was stored. Pure Jackson, configured as
 * Spring Boot configures it — no context, no database.
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

    private static Map<String, Integer> weights(int a, int b) {
        Map<String, Integer> w = new LinkedHashMap<>();
        w.put("A", a);
        w.put("B", b);
        return w;
    }

    /* ---------------------------------------------------------------- journeys */

    @Test
    void journeyView_withArms_writesTheArmBlock_andTheStepsDocumentAsStored() throws Exception {
        JourneyView j = new JourneyView("j1", "/tmf-api/campaignManagement/v4/journey/j1", "snap B", "paused",
                "SnapProbeEvent", null, null, null, 20, "marketing", 0,
                json.readTree("[{\"name\":\"A\",\"subject\":\"sa\",\"content\":\"ca\"},{\"name\":\"B\",\"subject\":\"sb\",\"content\":\"cb\"}]"),
                true, weights(50, 50), List.of(),
                json.readTree("[{\"type\":\"message\",\"subject\":\"s\",\"content\":\"c\"},{\"type\":\"wait\",\"minutes\":5}]"),
                null, T, "Journey");
        assertEquals("{\"id\":\"j1\",\"href\":\"/tmf-api/campaignManagement/v4/journey/j1\",\"name\":\"snap B\","
                + "\"status\":\"paused\",\"triggerEventType\":\"SnapProbeEvent\",\"holdoutPercent\":20,"
                + "\"category\":\"marketing\",\"priority\":0,"
                + "\"arms\":[{\"name\":\"A\",\"subject\":\"sa\",\"content\":\"ca\"},{\"name\":\"B\",\"subject\":\"sb\",\"content\":\"cb\"}],"
                + "\"autoTune\":true,\"armWeights\":{\"A\":50,\"B\":50},\"tuningLog\":[],"
                + "\"steps\":[{\"type\":\"message\",\"subject\":\"s\",\"content\":\"c\"},{\"type\":\"wait\",\"minutes\":5}],"
                + "\"lastUpdate\":\"2026-09-22T10:00:00Z\",\"@type\":\"Journey\"}", write(j));
    }

    @Test
    void journeyView_withoutArms_leavesTheArmBlockOff_andWritesTheEditStamp() throws Exception {
        JourneyView j = new JourneyView("j1", "h", "snap A2", "paused", null, "completed", "snap-seg", null, 0,
                "marketing", 4, null, null, null, null, json.readTree("[{\"type\":\"exit\"}]"), T, T, "Journey");
        assertEquals("{\"id\":\"j1\",\"href\":\"h\",\"name\":\"snap A2\",\"status\":\"paused\",\"triggerState\":\"completed\","
                + "\"segmentName\":\"snap-seg\",\"holdoutPercent\":0,\"category\":\"marketing\",\"priority\":4,"
                + "\"steps\":[{\"type\":\"exit\"}],\"stepsEditedAt\":\"2026-09-22T10:00:00Z\","
                + "\"lastUpdate\":\"2026-09-22T10:00:00Z\",\"@type\":\"Journey\"}", write(j));
    }

    @Test
    void journeyRequest_tellsAbsentFromExplicitNull_andReadsStepsAsAStringToo() throws Exception {
        JourneyRequest edit = json.readValue("{\"name\":\"n\",\"triggerEventType\":null,\"segmentName\":\"s\","
                + "\"steps\":\"[{\\\"type\\\":\\\"exit\\\"}]\",\"holdoutPercent\":\"5\",\"priority\":\"1\","
                + "\"autoTune\":\"true\",\"extraKeyIgnored\":true}", JourneyRequest.class);
        assertTrue(edit.triggerEventType().isNull(), "explicit null is an edit");
        assertNull(edit.triggerState(), "absent is not");
        assertNull(JourneyRequest.text(edit.triggerEventType()));
        assertEquals("s", JourneyRequest.text(edit.segmentName()));
        assertTrue(edit.steps().isTextual());
        assertEquals(5, edit.holdoutPercent());
        assertEquals(1, edit.priority());
        assertEquals(true, edit.autoTune());
        assertFalse(edit.mentionsArms());
        JourneyRequest arms = json.readValue("{\"messageVariants\":[{\"name\":\"A\"}]}", JourneyRequest.class);
        assertTrue(arms.mentionsArms());
        assertTrue(arms.armsDocument().isArray());
        JourneyRequest cleared = json.readValue("{\"arms\":null}", JourneyRequest.class);
        assertTrue(cleared.mentionsArms(), "arms: null clears the arms");
        assertTrue(cleared.armsDocument().isNull());
    }

    @Test
    void journeyStats_writesEveryBlockInItsSlot_andLeavesOffWhatTheMapLeftOff() throws Exception {
        Map<String, Long> atStep = new LinkedHashMap<>();
        atStep.put("step0", 1L);
        Map<String, Long> byStage = new LinkedHashMap<>();
        byStage.put("Welcome", 1L);
        byStage.put("Wait", 0L);
        JourneyStats a = new JourneyStats("j1", 3, 3, 0, atStep, byStage,
                List.of(new JourneyStats.FunnelNode(0, "message", "Welcome", 3, 1),
                        new JourneyStats.FunnelNode(1, "exit", null, 0, 0)),
                0, new Conversions(2, 0), null, null, null, 66.7, null, null, null,
                new JourneyStats.Revenue(new BigDecimal("49.90"), BigDecimal.ZERO, null,
                        "monthly recurring value of converting orders"),
                T, "steps were edited after launch — earlier enrollees walked a different version");
        assertEquals("{\"journeyId\":\"j1\",\"entered\":3,\"treated\":3,\"heldOut\":0,\"activeAtStep\":{\"step0\":1},"
                + "\"stageFunnel\":{\"Welcome\":1,\"Wait\":0},"
                + "\"funnel\":[{\"index\":0,\"type\":\"message\",\"stage\":\"Welcome\",\"reached\":3,\"active\":1},"
                + "{\"index\":1,\"type\":\"exit\",\"reached\":0,\"active\":0}],\"completedUnconverted\":0,"
                + "\"conversions\":{\"treated\":2,\"holdout\":0},\"treatedRate\":66.7,"
                + "\"revenue\":{\"treated\":49.90,\"holdout\":0,\"basis\":\"monthly recurring value of converting orders\"},"
                + "\"stepsEditedAt\":\"2026-09-22T10:00:00Z\","
                + "\"editNote\":\"steps were edited after launch — earlier enrollees walked a different version\"}",
                write(a));
        // with arms: the arm block sits between conversions and the rates; an empty stage funnel is left off
        TuneEntry waiting = new TuneEntry("2026-09-22T10:00:00Z",
                List.of(new ArmRow("A", 50, 1, 0, 0.0, BigDecimal.ZERO), new ArmRow("B", 50, 3, 0, 0.0, BigDecimal.ZERO)),
                weights(50, 50), null, null, "every arm needs at least 20 treated enrolments before it is judged",
                "waiting", weights(50, 50), "d1");
        JourneyStats b = new JourneyStats("j1", 6, 4, 2, atStep, new LinkedHashMap<>(), List.of(), 0,
                new Conversions(1, 0),
                List.of(new ArmRow("A", 50, 1, 0, 0.0, BigDecimal.ZERO), new ArmRow("B", 50, 3, 1, 33.3, new BigDecimal("20.00"))),
                true, List.of(waiting), 25.0, 0.0, 25.0,
                "holdout under 5 people — the lift is an anecdote, not a measurement",
                new JourneyStats.Revenue(new BigDecimal("20.00"), BigDecimal.ZERO, new BigDecimal("5.00"),
                        "monthly recurring value of converting orders"), null, null);
        assertEquals("{\"journeyId\":\"j1\",\"entered\":6,\"treated\":4,\"heldOut\":2,\"activeAtStep\":{\"step0\":1},"
                + "\"funnel\":[],\"completedUnconverted\":0,\"conversions\":{\"treated\":1,\"holdout\":0},"
                + "\"arms\":[{\"name\":\"A\",\"weight\":50,\"enrolled\":1,\"converted\":0,\"rate\":0.0,\"revenue\":0},"
                + "{\"name\":\"B\",\"weight\":50,\"enrolled\":3,\"converted\":1,\"rate\":33.3,\"revenue\":20.00}],"
                + "\"autoTune\":true,\"tuningLog\":[{\"at\":\"2026-09-22T10:00:00Z\","
                + "\"arms\":[{\"name\":\"A\",\"weight\":50,\"enrolled\":1,\"converted\":0,\"rate\":0.0,\"revenue\":0},"
                + "{\"name\":\"B\",\"weight\":50,\"enrolled\":3,\"converted\":0,\"rate\":0.0,\"revenue\":0}],"
                + "\"before\":{\"A\":50,\"B\":50},\"why\":\"every arm needs at least 20 treated enrolments before it is judged\","
                + "\"decision\":\"waiting\",\"after\":{\"A\":50,\"B\":50},\"decisionId\":\"d1\"}],"
                + "\"treatedRate\":25.0,\"holdoutRate\":0.0,\"liftPoints\":25.0,"
                + "\"note\":\"holdout under 5 people — the lift is an anecdote, not a measurement\","
                + "\"revenue\":{\"treated\":20.00,\"holdout\":0,\"liftPerCustomer\":5.00,"
                + "\"basis\":\"monthly recurring value of converting orders\"}}", write(b));
    }

    @Test
    void tuneLedger_rowsReadBackAndRewriteTheSameBytes_zAndThresholdOnlyWhenJudged() throws Exception {
        String stored = "[{\"at\":\"2026-09-22T10:00:00Z\",\"arms\":[{\"name\":\"A\",\"weight\":50,\"enrolled\":60,"
                + "\"converted\":30,\"rate\":50.0,\"revenue\":120.00}],\"before\":{\"A\":50,\"B\":50},\"z\":4.71,"
                + "\"threshold\":1.64,\"why\":\"\\\"A\\\" converts at 50.0 % vs 10.0 % for \\\"B\\\" (z 4.71 ≥ 1.64)\","
                + "\"decision\":\"shift\",\"after\":{\"A\":90,\"B\":10},\"decisionId\":\"d2\"},"
                // a row from before the decision log existed has no decisionId
                + "{\"at\":\"2026-09-21T10:00:00Z\",\"arms\":[],\"before\":{},\"why\":\"fewer than two arms\","
                + "\"decision\":\"hold\",\"after\":{}}]";
        List<TuneEntry> rows = json.readValue(stored, json.getTypeFactory().constructCollectionType(List.class, TuneEntry.class));
        assertEquals(stored, write(rows));
        assertEquals(new BigDecimal("120.00"), rows.get(0).arms().get(0).revenue());
        TuneResult result = new TuneResult(rows.get(1), "j1");
        assertEquals("{\"at\":\"2026-09-21T10:00:00Z\",\"arms\":[],\"before\":{},\"why\":\"fewer than two arms\","
                + "\"decision\":\"hold\",\"after\":{},\"journeyId\":\"j1\"}", write(result));
    }

    @Test
    void enrolmentReceipts_pinTheKeysMapOfHadRandomised() throws Exception {
        Map<String, String> dealt = new LinkedHashMap<>();
        dealt.put("p1", "holdout");
        dealt.put("p2", "");
        assertEquals("{\"journeyId\":\"j1\",\"enrolled\":2,\"dealt\":{\"p1\":\"holdout\",\"p2\":\"\"}}",
                write(new EnrollmentReceipt("j1", 2, dealt)));
        assertEquals("{\"journeyId\":\"j1\",\"segment\":\"vip\",\"enrolled\":0}",
                write(new SegmentEnrollmentReceipt("j1", "vip", 0)));
        assertEquals("{\"journeyId\":\"j1\",\"partyId\":\"p1\",\"status\":\"converted\",\"arm\":\"\"}",
                write(new ConversionReceipt("j1", "p1", "converted", "")));
        assertEquals("{\"partyId\":\"p1\",\"winnerJourneyId\":null,\"heldJourneyId\":\"j2\",\"reason\":\"r\","
                + "\"decidedAt\":\"2026-09-22T10:00:00Z\"}",
                write(new ArbitrationDecisionView("p1", null, "j2", "r", T, null)));
        EnrollmentRequest req = json.readValue("{\"partyIds\":[\"p1\",7],\"context\":{\"order\":{\"id\":\"o-1\"}}}",
                EnrollmentRequest.class);
        assertEquals(List.of("p1", "7"), req.partyIds());
        assertEquals("o-1", req.context().path("order").path("id").asText());
    }

    /* ---------------------------------------------------------------- campaigns */

    @Test
    void campaignView_writesTheMessagePinned_andArmsOnlyWhenPresent() throws Exception {
        CampaignView c = new CampaignView("c1", "h", "snap C1", "active", "SnapProbeEvent", "completed",
                new CampaignView.Message("Welcome", "Use {code}"), "SNAP", "snap-none", null, null, 10, 14,
                "SnapConvert", "Campaign");
        assertEquals("{\"id\":\"c1\",\"href\":\"h\",\"name\":\"snap C1\",\"status\":\"active\","
                + "\"triggerEventType\":\"SnapProbeEvent\",\"triggerState\":\"completed\","
                + "\"message\":{\"subject\":\"Welcome\",\"content\":\"Use {code}\"},\"promotionCode\":\"SNAP\","
                + "\"segmentName\":\"snap-none\",\"holdoutPercent\":10,\"conversionWindowDays\":14,"
                + "\"conversionEvent\":\"SnapConvert\",\"@type\":\"Campaign\"}", write(c));
        CampaignView arms = new CampaignView("c2", "h", "snap C2", "active", null, null,
                new CampaignView.Message("sa", "ca"), null, null, "aud-1",
                List.of(new ArmSpec("A", "sa", "ca"), new ArmSpec("B", "sb", "cb")), 0, 7, null, "Campaign");
        assertEquals("{\"id\":\"c2\",\"href\":\"h\",\"name\":\"snap C2\",\"status\":\"active\",\"triggerEventType\":null,"
                + "\"message\":{\"subject\":\"sa\",\"content\":\"ca\"},\"audienceRef\":\"aud-1\","
                + "\"messageVariants\":[{\"name\":\"A\",\"subject\":\"sa\",\"content\":\"ca\"},"
                + "{\"name\":\"B\",\"subject\":\"sb\",\"content\":\"cb\"}],\"holdoutPercent\":0,"
                + "\"conversionWindowDays\":7,\"@type\":\"Campaign\"}", write(arms));
        CampaignRequest req = json.readValue("{\"name\":\"n\",\"messageVariants\":[{\"name\":\"A\",\"subject\":\"s\","
                + "\"content\":\"c\",\"extra\":1}],\"holdoutPercent\":\"10\",\"unknown\":true}", CampaignRequest.class);
        assertEquals(new ArmSpec("A", "s", "c"), req.messageVariants().get(0));
        assertEquals(10, req.holdoutPercent());
        assertNull(req.message());
    }

    @Test
    void campaignStats_writesTheArmReadoutWithANullRate_andTheVerdictOnlyWhenJudged() throws Exception {
        CampaignStats s = new CampaignStats("c2", 0, 0, new Conversions(0, 0), null, null, null, 7, null, null,
                new CampaignStats.ArmReadout(List.of(new CampaignStats.ArmStat("A", "sa", 0, 0, null)), null, null));
        assertEquals("{\"campaignId\":\"c2\",\"reached\":0,\"heldOut\":0,\"conversions\":{\"treated\":0,\"holdout\":0},"
                + "\"conversionWindowDays\":7,\"arms\":{\"arms\":[{\"name\":\"A\",\"subject\":\"sa\",\"sent\":0,"
                + "\"conversions\":0,\"rate\":null}]}}", write(s));
        CampaignStats measured = new CampaignStats("c1", 40, 4, new Conversions(10, 1), 25.0, 25.0, 0.0, 7,
                "holdout under 5 people — the lift is an anecdote, not a measurement",
                new CampaignStats.Revenue(new BigDecimal("349.00"), new BigDecimal("49.90"), new BigDecimal("8.73"),
                        new BigDecimal("12.48"), new BigDecimal("-3.75"), "monthly recurring value of converting orders"),
                new CampaignStats.ArmReadout(List.of(new CampaignStats.ArmStat("A", "sa", 20, 6, 30.0),
                        new CampaignStats.ArmStat("B", "sb", 20, 4, 20.0)), "A",
                        "arms under 30 people — the split is an anecdote, keep the test running"));
        assertEquals("{\"campaignId\":\"c1\",\"reached\":40,\"heldOut\":4,\"conversions\":{\"treated\":10,\"holdout\":1},"
                + "\"treatedRate\":25.0,\"holdoutRate\":25.0,\"liftPoints\":0.0,\"conversionWindowDays\":7,"
                + "\"note\":\"holdout under 5 people — the lift is an anecdote, not a measurement\","
                + "\"revenue\":{\"treated\":349.00,\"holdout\":49.90,\"treatedPerCustomer\":8.73,"
                + "\"holdoutPerCustomer\":12.48,\"liftPerCustomer\":-3.75,\"basis\":\"monthly recurring value of converting orders\"},"
                + "\"arms\":{\"arms\":[{\"name\":\"A\",\"subject\":\"sa\",\"sent\":20,\"conversions\":6,\"rate\":30.0},"
                + "{\"name\":\"B\",\"subject\":\"sb\",\"sent\":20,\"conversions\":4,\"rate\":20.0}],\"leader\":\"A\","
                + "\"verdict\":\"arms under 30 people — the split is an anecdote, keep the test running\"}}",
                write(measured));
        assertEquals("{\"campaignId\":\"c1\",\"audience\":\"vip\",\"reached\":3}",
                write(new ExecutionReceipt("c1", "vip", 3)));
        assertEquals("{\"id\":\"e1\",\"party\":{\"id\":\"p1\"},\"executedAt\":\"2026-09-22T10:00Z\",\"@type\":\"CampaignExecution\"}",
                write(new CampaignExecutionView("e1", new CampaignExecutionView.PartyRef("p1"), T.toString(), "CampaignExecution")));
    }

    @Test
    void attributionReport_writesIncrementalAsNullWithoutAControlGroup_andRevenueOnlyWhenThereIsAny() throws Exception {
        AttributionReport.Program p = new AttributionReport.Program("journey", "j1", "snap", "paused", null, 3, 0,
                new Conversions(2, 0), 66.7, null, null,
                new AttributionReport.ProgramRevenue(new BigDecimal("49.90"), BigDecimal.ZERO, null));
        AttributionReport.Program bare = new AttributionReport.Program("campaign", "c1", "snap", "draft", "X", 0, 0,
                new Conversions(0, 0), null, null, null, null);
        Map<String, AttributionReport.ChannelTotals> byChannel = new LinkedHashMap<>();
        byChannel.put("campaign", new AttributionReport.ChannelTotals(1, 0, BigDecimal.ZERO));
        AttributionReport r = new AttributionReport(List.of(p, bare),
                new AttributionReport.Portfolio(2, 3, 0, new Conversions(2, 0), 66.7, null, null,
                        new AttributionReport.PortfolioRevenue(new BigDecimal("49.90"), BigDecimal.ZERO, BigDecimal.ZERO,
                                "monthly recurring value of converting orders; incremental = holdout-adjusted"), null),
                byChannel);
        assertEquals("{\"programs\":[{\"type\":\"journey\",\"id\":\"j1\",\"name\":\"snap\",\"status\":\"paused\","
                + "\"conversionEvent\":null,\"reached\":3,\"heldOut\":0,\"conversions\":{\"treated\":2,\"holdout\":0},"
                + "\"treatedRate\":66.7,\"revenue\":{\"treated\":49.90,\"holdout\":0,\"incremental\":null}},"
                + "{\"type\":\"campaign\",\"id\":\"c1\",\"name\":\"snap\",\"status\":\"draft\",\"conversionEvent\":\"X\","
                + "\"reached\":0,\"heldOut\":0,\"conversions\":{\"treated\":0,\"holdout\":0}}],"
                + "\"portfolio\":{\"programs\":2,\"totalReached\":3,\"totalHeldOut\":0,\"conversions\":{\"treated\":2,\"holdout\":0},"
                + "\"blendedTreatedRate\":66.7,\"revenue\":{\"grossAttributed\":49.90,\"holdout\":0,\"incremental\":0,"
                + "\"basis\":\"monthly recurring value of converting orders; incremental = holdout-adjusted\"}},"
                + "\"byChannel\":{\"campaign\":{\"programs\":1,\"reached\":0,\"attributedRevenue\":0}}}", write(r));
    }

    /* ---------------------------------------------------------------- guardrails */

    @Test
    void martechSettings_writeQuietHoursOnlyWhenSet() throws Exception {
        assertEquals("{\"maxMarketingMessages\":0,\"perDays\":1,\"capActive\":false,\"quietActive\":false,\"@type\":\"MartechSetting\"}",
                write(new MartechSettingsView(0, 1, false, null, null, null, false, "MartechSetting")));
        assertEquals("{\"maxMarketingMessages\":3,\"perDays\":7,\"capActive\":true,\"quietStart\":\"22:00\",\"quietEnd\":\"07:00\","
                + "\"timeZone\":\"Europe/Oslo\",\"quietActive\":true,\"@type\":\"MartechSetting\"}",
                write(new MartechSettingsView(3, 7, true, "22:00", "07:00", "Europe/Oslo", true, "MartechSetting")));
    }

    /* ---------------------------------------------------------------- learning contracts + decisions */

    @Test
    void learningContractView_putsThePointsKeysFirstRelabelled_thenTheContractInItsOwnShape() throws Exception {
        DecisionPointView point = new DecisionPointView("journey.nextBestAction", "party", "medium", "d",
                "priority-first", "1", "campaign", "DecisionPoint");
        assertEquals("{\"name\":\"journey.nextBestAction\",\"subjectType\":\"party\",\"autonomy\":\"medium\","
                + "\"description\":\"d\",\"policy\":\"priority-first\",\"policyVersion\":\"1\",\"source\":\"campaign\","
                + "\"@type\":\"DecisionPoint\"}", write(point));
        LearningContractView defaults = LearningContractView.of(point, ContractView.Defaults.withObjective(null));
        assertEquals("{\"name\":\"journey.nextBestAction\",\"subjectType\":\"party\",\"autonomy\":\"medium\","
                + "\"description\":\"d\",\"policy\":\"priority-first\",\"policyVersion\":\"1\",\"source\":\"campaign\","
                + "\"@type\":\"LearningContract\",\"contract\":{\"objective\":null,\"secondaryMetrics\":[],\"guardrails\":[],"
                + "\"allowedActions\":null,\"explorationMaxPercent\":null,\"autonomy\":null,\"fallbackAction\":null,"
                + "\"enabled\":true,\"version\":0,\"defaults\":true}}", write(defaults));
        LearningContractView stored = LearningContractView.of(point, new ContractView.Stored("c-1", 1, "c-1@1",
                "conversion", List.of("revenue", "reach"), List.of("no marketing without consent"),
                List.of("hold", "shift"), 15, "medium", "hold", true, "snap", "demo", T, false));
        assertTrue(write(stored).endsWith("\"@type\":\"LearningContract\",\"contract\":{\"id\":\"c-1\",\"version\":1,"
                + "\"ref\":\"c-1@1\",\"objective\":\"conversion\",\"secondaryMetrics\":[\"revenue\",\"reach\"],"
                + "\"guardrails\":[\"no marketing without consent\"],\"allowedActions\":[\"hold\",\"shift\"],"
                + "\"explorationMaxPercent\":15,\"autonomy\":\"medium\",\"fallbackAction\":\"hold\",\"enabled\":true,"
                + "\"notes\":\"snap\",\"updatedBy\":\"demo\",\"lastUpdate\":\"2026-09-22T10:00:00Z\",\"defaults\":false}}"),
                write(stored));
        LearningContractRequest req = json.readValue("{\"secondaryMetrics\":\"revenue, reach\",\"guardrails\":[\"a\",\" \"],"
                + "\"explorationMaxPercent\":\"15\",\"enabled\":\"false\",\"allowedActions\":null}", LearningContractRequest.class);
        assertTrue(req.secondaryMetrics().isTextual());
        assertTrue(req.guardrails().isArray());
        assertEquals("15", req.explorationMaxPercent().asText());
        assertEquals("false", req.enabled().asText());
        assertTrue(req.allowedActions().isNull());
        DryRunRequest dry = json.readValue("{\"context\":{\"partyId\":\"p1\"},\"candidates\":[\"a\",\"b\"]}", DryRunRequest.class);
        assertEquals(List.of("a", "b"), dry.candidates());
        assertNull(dry.subjectId());
    }

    @Test
    void decisionView_writesTheRecordsKeysInTheLogsOrder_andADryRunDropsTheIdAndAddsItsFlag() throws Exception {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("seed", "dry-run");
        ctx.put("partyId", "p1");
        DecisionRecord r = new DecisionRecord("d1", "journey.nextBestAction", "party", "p1", List.of("j1", "j2"),
                List.of("j1", "j2"), List.of("learning-contract: paused"), "j1", null, "priority-first", "1",
                "learning contract paused — fallback answers", ctx, Map.of(), "medium", true, "campaign", T, "c-1@2");
        assertEquals("{\"decisionId\":\"d1\",\"decisionPoint\":\"journey.nextBestAction\",\"subjectType\":\"party\","
                + "\"subjectId\":\"p1\",\"candidates\":[\"j1\",\"j2\"],\"eligibleActions\":[\"j1\",\"j2\"],"
                + "\"constraints\":[\"learning-contract: paused\"],\"action\":\"j1\",\"propensity\":null,"
                + "\"policy\":\"priority-first\",\"policyVersion\":\"1\",\"reason\":\"learning contract paused — fallback answers\","
                + "\"context\":{\"seed\":\"dry-run\",\"partyId\":\"p1\"},\"evidence\":{},\"autonomy\":\"medium\",\"fallback\":true,"
                + "\"source\":\"campaign\",\"contract\":\"c-1@2\",\"decidedAt\":\"2026-09-22T10:00Z\",\"@type\":\"Decision\"}",
                write(r.view()));
        String dry = write(DecisionDryRun.of(r.view()));
        assertTrue(dry.startsWith("{\"decisionPoint\":\"journey.nextBestAction\","), dry);
        assertTrue(dry.endsWith("\"decidedAt\":\"2026-09-22T10:00Z\",\"@type\":\"DecisionDryRun\",\"dryRun\":true}"), dry);
        assertFalse(dry.contains("decisionId"), dry);
        DecisionRecord propensity = new DecisionRecord("d2", "journey.enrolment", "party", "p1", List.of("holdout", "A"),
                List.of("holdout", "A"), List.of(), "A", 0.9, "holdout-then-weighted-hash", "1", "why", Map.of(),
                Map.of(), "high", false, "campaign", T, null);
        assertTrue(write(propensity.view()).contains("\"action\":\"A\",\"propensity\":0.9,"));
    }

    /* ---------------------------------------------------------------- referral, audience, privacy */

    @Test
    void referralRecords_writeTheirKeysInOrder_rewardAtItsConfiguredScale() throws Exception {
        assertEquals("{\"code\":\"CYPNEUG5\",\"rewardGb\":5,\"joined\":0,\"rewarded\":0,\"pending\":0}",
                write(new ReferralCodeView("CYPNEUG5", new BigDecimal("5"), 0, 0, 0)));
        assertEquals("{\"code\":\"CYPNEUG5\",\"status\":\"pending\",\"rewardGb\":5,\"note\":\"n\"}",
                write(new RedeemReceipt("CYPNEUG5", "pending", new BigDecimal("5"), "n")));
        assertEquals("{\"code\":\"CYPNEUG5\",\"clubOrgId\":\"\"}", write(new ClubLinkReceipt("CYPNEUG5", "")));
        assertEquals("{\"id\":\"g1\",\"name\":\"goal\",\"areaCode\":\"4021\",\"target\":10,\"joined\":3,\"percent\":30,\"unlocked\":false}",
                write(new CommunityGoalView("g1", "goal", "4021", 10, 3, 30, false)));
        assertEquals("{\"@type\":\"ReferralReport\",\"conversions\":1,\"rewarded\":1,\"pending\":0,\"held\":0,\"rewardCostGb\":10,"
                + "\"rows\":[{\"code\":\"C\",\"referrerPartyId\":\"r\",\"joinerPartyId\":\"j\",\"status\":\"rewarded\","
                + "\"createdAt\":\"2026-09-22T10:00:00Z\"}]}",
                write(new ReferralReport("ReferralReport", 1, 1, 0, 0, new BigDecimal("10"),
                        List.of(new ReferralReport.Row("C", "r", "j", "rewarded", T)))));
        assertEquals("{\"segment\":\"s\",\"audienceId\":\"a\",\"members\":2,\"withEmail\":1,\"pushed\":1,\"schema\":\"EMAIL_SHA256\"}",
                write(new AudienceSyncResult("s", "a", 2, 1, 1, "EMAIL_SHA256")));
        assertEquals("{\"category\":\"marketing\",\"count\":0,\"items\":{\"journeyEnrollments\":[],\"marketingTouches\":[]}}",
                write(new PrivacyExport("marketing", 0, new PrivacyExport.Items(List.of(), List.of()))));
        assertEquals("{\"category\":\"marketing\",\"deleted\":1,\"retained\":0}", write(new EraseReceipt("marketing", 1, 0)));
    }
}
