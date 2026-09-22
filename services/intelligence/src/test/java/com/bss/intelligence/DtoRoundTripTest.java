package com.bss.intelligence;

import com.bss.intelligence.churn.ChurnLearning;
import com.bss.intelligence.controller.AuditController;
import com.bss.intelligence.controller.Tmf724Controller;
import com.bss.intelligence.incident.IncidentRunbookView;
import com.bss.intelligence.incident.IncidentStats;
import com.bss.intelligence.incident.IncidentTraceView;
import com.bss.intelligence.incident.VerdictRequest;
import com.bss.intelligence.risk.PartyRef;
import com.bss.intelligence.risk.RiskAssessmentView;
import com.bss.intelligence.service.AiModelContractView;
import com.bss.intelligence.service.AiModelView;
import com.bss.intelligence.service.ContractPatch;
import com.bss.intelligence.sim.PriceSimReportView;
import com.bss.intelligence.workforce.ApprovalView;
import com.bss.intelligence.workforce.WorkforceRequests;
import com.bss.intelligence.workforce.WorkforceTaskView;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The intelligence records serialise as the maps did: keys in the order the
 * maps put them, absent-when-null only where the maps left the key off,
 * numbers as carried, open documents (JsonNode) verbatim. Pure Jackson,
 * configured as Spring Boot configures it — no context, no database.
 */
class DtoRoundTripTest {

    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private static final OffsetDateTime T = OffsetDateTime.of(2026, 9, 22, 10, 0, 0, 0, ZoneOffset.UTC);

    @Test
    void workforceTask_optionalKeysOffUntilSet_selfReportedInnerKeysStay() throws Exception {
        WorkforceTaskView open = new WorkforceTaskView("ticket~t1", "ticket", "t1", "[major] No signal",
                "claimed", "w1", "hermes", T, T.plusMinutes(15), null, null, null);
        assertEquals("{\"id\":\"ticket~t1\",\"kind\":\"ticket\",\"subjectRef\":\"t1\",\"summary\":\"[major] No signal\","
                + "\"status\":\"claimed\",\"claimedBy\":\"w1\",\"claimedByName\":\"hermes\","
                + "\"claimedAt\":\"2026-09-22T10:00:00Z\",\"leaseUntil\":\"2026-09-22T10:15:00Z\"}",
                json.writeValueAsString(open));
        WorkforceTaskView done = new WorkforceTaskView("ticket~t1", "ticket", "t1", "s", "completed", "w1", null,
                T, T, "resolved", new WorkforceTaskView.SelfReported(120, null, null), T);
        assertTrue(json.writeValueAsString(done).endsWith(
                "\"claimedByName\":null,\"claimedAt\":\"2026-09-22T10:00:00Z\",\"leaseUntil\":\"2026-09-22T10:00:00Z\","
                + "\"outcome\":\"resolved\",\"selfReported\":{\"tokens\":120,\"costMicros\":null,\"model\":null},"
                + "\"completedAt\":\"2026-09-22T10:00:00Z\"}"));
    }

    @Test
    void approval_bodyAndResultAreTheCallersJsonVerbatim() throws Exception {
        JsonNode body = json.readTree("{\"z\":1,\"a\":[true,null],\"nested\":{\"k\":\"v\"}}");
        ApprovalView filed = new ApprovalView("apr_1", "refund", "POST", "/tmf-api/x", body, "why", "pending",
                "w1", "hermes", T, null, null, null, null, null);
        assertEquals("{\"id\":\"apr_1\",\"action\":\"refund\",\"method\":\"POST\",\"path\":\"/tmf-api/x\","
                + "\"body\":{\"z\":1,\"a\":[true,null],\"nested\":{\"k\":\"v\"}},\"reason\":\"why\",\"status\":\"pending\","
                + "\"requestedBy\":\"w1\",\"requestedByName\":\"hermes\",\"createdAt\":\"2026-09-22T10:00:00Z\"}",
                json.writeValueAsString(filed));
        WorkforceRequests.FileApprovalRequest req = json.readValue(
                "{\"action\":\"refund\",\"method\":\"post\",\"path\":\"/tmf-api/x\",\"reason\":\"r\",\"body\":{\"b\":2},\"extra\":1}",
                WorkforceRequests.FileApprovalRequest.class);
        assertEquals("post", req.method());
        assertEquals("{\"b\":2}", req.body().toString());
    }

    @Test
    void priceSimReport_nullOptionalsOff_idAndNameOnlyOnceSaved() throws Exception {
        PriceSimReportView.Line line = new PriceSimReportView.Line("Plan", 2, new BigDecimal("100.00"),
                new BigDecimal("110"), new BigDecimal("20.00"), new BigDecimal("240.00"), null, null, null, 0, null);
        PriceSimReportView report = new PriceSimReportView("PriceSimulationReport", List.of(line),
                new BigDecimal("240.00"), "EUR", 0, List.of("a"), new PriceSimReportView.Basis(2, 5, "now"),
                null, null);
        String s = json.writeValueAsString(report);
        assertTrue(s.startsWith("{\"@type\":\"PriceSimulationReport\",\"lines\":[{\"offeringName\":\"Plan\",\"subscribers\":2,"
                + "\"currentMonthly\":100.00,\"proposedMonthly\":110,\"monthlyRevenueDelta\":20.00,"
                + "\"annualRevenueDelta\":240.00,\"subscribersAtChurnRisk\":0}],"), s);
        assertTrue(s.endsWith("\"basis\":{\"activeProducts\":2,\"offeringsInCatalog\":5,\"asOf\":\"now\"}}"), s);
        assertTrue(json.writeValueAsString(report.saved("r1", "n")).endsWith("\"id\":\"r1\",\"name\":\"n\"}"));
    }

    @Test
    void riskAssessment_tmf696ShapeWithOpenEvidence() throws Exception {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("count", 2);
        evidence.put("amountDue", new BigDecimal("199.50"));
        RiskAssessmentView v = new RiskAssessmentView("ra-1", "/tmf-api/riskManagement/v4/partyRiskAssessment/ra-1",
                "completed", List.of(new PartyRef("p1", null)),
                new RiskAssessmentView.RiskResult(40, "medium",
                        List.of(new RiskAssessmentView.RiskSignal("unpaidBills", 40, "two unpaid", evidence))),
                T, "PartyRiskAssessment");
        assertEquals("{\"id\":\"ra-1\",\"href\":\"/tmf-api/riskManagement/v4/partyRiskAssessment/ra-1\",\"status\":\"completed\","
                + "\"relatedParty\":[{\"id\":\"p1\"}],\"riskAssessmentResult\":{\"overallScore\":40,\"riskLevel\":\"medium\","
                + "\"signal\":[{\"name\":\"unpaidBills\",\"points\":40,\"label\":\"two unpaid\",\"evidence\":{\"count\":2,\"amountDue\":199.50}}]},"
                + "\"assessedAt\":\"2026-09-22T10:00:00Z\",\"@type\":\"PartyRiskAssessment\"}",
                json.writeValueAsString(v));
        // the stored result JSON reads back, unknown keys ignored
        RiskAssessmentView.RiskSignal back = json.readValue(
                "{\"name\":\"x\",\"points\":1,\"label\":\"l\",\"evidence\":{\"a\":1},\"future\":true}",
                RiskAssessmentView.RiskSignal.class);
        assertEquals(1, back.evidence().get("a"));
    }

    @Test
    void churnStatus_trainedKeysOnlyWhenTrained() throws Exception {
        assertEquals("{\"features\":[\"f\"],\"snapshots\":3,\"labeledOutcomes\":0,\"trained\":false}",
                json.writeValueAsString(new ChurnLearning.ChurnModelStatus(List.of("f"), 3, 0, false, null, null, null)));
        assertEquals("{\"party\":{\"id\":\"p\"},\"churned\":true}",
                json.writeValueAsString(new ChurnLearning.OutcomeReceipt(new ChurnLearning.PartyRef("p"), true)));
    }

    @Test
    void incidentTrace_andTmf724Incident() throws Exception {
        IncidentTraceView t = new IncidentTraceView("i1", "spec:task", "f1", "o1", "It timed out", new BigDecimal("0.72"),
                null, "llm", null, "pending", null, 812L, T, "IncidentTrace");
        assertEquals("{\"id\":\"i1\",\"signature\":\"spec:task\",\"processFlowId\":\"f1\",\"productOrderId\":\"o1\","
                + "\"hypothesis\":\"It timed out\",\"confidence\":0.72,\"source\":\"llm\",\"verdict\":\"pending\","
                + "\"diagnoseMs\":812,\"createdAt\":\"2026-09-22T10:00:00Z\",\"@type\":\"IncidentTrace\"}",
                json.writeValueAsString(t));
        Tmf724Controller.Incident inc = json.readValue(json.writeValueAsString(
                new Tmf724Controller.Incident("i1", "/h", "n", "process", "acknowledged", "unacknowledged", T,
                        List.of(new Tmf724Controller.Ref("f1", "ProcessFlow")),
                        new Tmf724Controller.RootCause("h", new BigDecimal("0.5"), "llm"), null, "Incident")),
                Tmf724Controller.Incident.class);
        assertNull(inc.troubleTicket());
        assertEquals("ProcessFlow", inc.sourceObject().get(0).referredType());
        assertEquals("{\"traces\":2,\"fromLlm\":1,\"fromRunbook\":1,\"autoDiagnosedRate\":50.0,"
                + "\"verdicts\":{\"useful\":1,\"notUseful\":0,\"pending\":1},\"@type\":\"IncidentStats\"}",
                json.writeValueAsString(new IncidentStats(2, 1, 1, 50.0, new IncidentStats.Verdicts(1, 0, 1), "IncidentStats")));
        assertTrue(json.writeValueAsString(new IncidentRunbookView("r", "s", 1, "proposed", "t", "d", "a", "[]", T,
                null, null, "IncidentRunbook")).contains("\"createdAt\":\"2026-09-22T10:00:00Z\",\"@type\""));
        VerdictRequest v = json.readValue("{\"useful\":true,\"note\":\"n\",\"x\":1}", VerdictRequest.class);
        assertEquals(Boolean.TRUE, v.useful());
        assertNull(json.readValue("{\"note\":\"n\"}", VerdictRequest.class).useful());
    }

    @Test
    void tmf915Projections_keepTheirKeyOrderAndLeaveOffWhatTheyNeverWrote() throws Exception {
        AiModelView served = new AiModelView("stub/stub-1", "/tmf-api/aiManagement/v4/aiModel/stub/stub-1", "stub-1",
                "stub", "languageModel", "active", List.of("FAST"), null, List.of("campaign-copy"), "AIModel");
        assertEquals("{\"id\":\"stub/stub-1\",\"href\":\"/tmf-api/aiManagement/v4/aiModel/stub/stub-1\",\"name\":\"stub-1\","
                + "\"provider\":\"stub\",\"category\":\"languageModel\",\"state\":\"active\",\"tier\":[\"FAST\"],"
                + "\"servedContract\":[\"campaign-copy\"],\"@type\":\"AIModel\"}", json.writeValueAsString(served));
        AiModelView churn = new AiModelView("local/churn-logistic", "/h", "churn-logistic", "local", "trainedClassifier",
                "active", null, new AiModelView.TrainingRecord(10, 3, T), List.of("churn-sweep"), "AIModel");
        assertTrue(json.writeValueAsString(churn).contains("\"state\":\"active\",\"trainingRecord\":{\"sampleCount\":10,"
                + "\"positives\":3,\"trainedAt\":\"2026-09-22T10:00:00Z\"},\"servedContract\""));

        AiModelContractView c = new AiModelContractView("campaign-copy", "/h", "campaign-copy", "active", null, null,
                new AiModelContractView.Monitoring(3, 30, 12, 84, 7, null),
                new AiModelContractView.Guardrail("armed", 0, 720), "AIModelContract");
        assertEquals("{\"id\":\"campaign-copy\",\"href\":\"/h\",\"name\":\"campaign-copy\",\"state\":\"active\","
                + "\"monitoring\":{\"calls\":3,\"promptTokens\":30,\"completionTokens\":12,\"costMicros\":84,\"avgLatencyMs\":7},"
                + "\"guardrail\":{\"tenantKillSwitch\":\"armed\",\"budgetMicros\":0,\"windowHours\":720},\"@type\":\"AIModelContract\"}",
                json.writeValueAsString(c));
        AiModelContractView decided = new AiModelContractView("x", "/h", "x", "suspended",
                new AiModelContractView.LastDecision(T, null), List.of(new AiModelContractView.ModelRef("stub/s", "AIModel")),
                null, new AiModelContractView.Guardrail("armed", 0, 720), "AIModelContract");
        assertTrue(json.writeValueAsString(decided).contains(
                "\"lastDecision\":{\"decidedAt\":\"2026-09-22T10:00:00Z\"},\"servedBy\":[{\"id\":\"stub/s\",\"@referredType\":\"AIModel\"}],\"guardrail\""));
        // the projection as a tree filters and selects like a stored document
        JsonNode tree = json.valueToTree(c);
        assertEquals("campaign-copy", tree.get("id").asText());
        assertEquals("suspended", json.readValue("{\"state\":\"suspended\",\"@type\":\"AIModelContract\"}", ContractPatch.class).state());
    }

    @Test
    void copilotReply_proposalIsTheModelsDocument_extrasRideAfter() throws Exception {
        JsonNode proposal = json.readTree("{\"offering\":{\"name\":\"StreamPlus\",\"price\":9.99},\"specs\":[]}");
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("clarify", "Which category?");
        com.bss.intelligence.service.CopilotReply r = new com.bss.intelligence.service.CopilotReply(
                "proposal", "Here is a proposal", proposal, "stub", "stub-1", null, extra);
        assertEquals("{\"kind\":\"proposal\",\"message\":\"Here is a proposal\","
                + "\"proposal\":{\"offering\":{\"name\":\"StreamPlus\",\"price\":9.99},\"specs\":[]},"
                + "\"provider\":\"stub\",\"model\":\"stub-1\",\"clarify\":\"Which category?\"}", json.writeValueAsString(r));
        com.bss.intelligence.service.CopilotRequests.CopilotChatRequest req = json.readValue(
                "{\"messages\":[{\"role\":\"owner\",\"content\":\"hi\",\"ts\":1}],\"catalog\":{\"offerings\":[]},\"x\":1}",
                com.bss.intelligence.service.CopilotRequests.CopilotChatRequest.class);
        assertEquals("owner", req.messages().get(0).role());
        assertTrue(req.catalog().has("offerings"));
    }

    @Test
    void chatAndKnowledge_optionalKeysAsTheMapsLeftThem() throws Exception {
        assertEquals("{\"reply\":null,\"status\":\"agent\"}", json.writeValueAsString(
                new com.bss.intelligence.chat.ChatViews.ChatTurn(null, "agent", null)));
        assertEquals("{\"reply\":\"Sorry\",\"status\":\"escalated\",\"ticketId\":\"t1\"}", json.writeValueAsString(
                new com.bss.intelligence.chat.ChatViews.ChatTurn("Sorry", "escalated", "t1")));
        assertEquals("{\"answer\":\"a\",\"sources\":[{\"id\":\"k1\",\"title\":\"Bills\"}],\"provider\":\"stub\",\"model\":\"s\"}",
                json.writeValueAsString(new com.bss.intelligence.service.KnowledgeAnswer(null, "a",
                        List.of(new com.bss.intelligence.service.KnowledgeAnswer.KnowledgeSource("k1", "Bills")), "stub", "s", null)));
        assertEquals("{}", json.writeValueAsString(com.bss.intelligence.service.OfferRef.NONE));
    }

    @Test
    void auditView_newColumnsRideAtTheEnd() throws Exception {
        AuditController.AiAuditView v = new AuditController.AiAuditView("a", "ticket-reply", "stub", "stub-1",
                "p", "r", "2026-09-22T10:00:00Z", 12, 24, "ok", null, 2, false);
        assertEquals("{\"id\":\"a\",\"useCase\":\"ticket-reply\",\"provider\":\"stub\",\"model\":\"stub-1\",\"prompt\":\"p\","
                + "\"response\":\"r\",\"createdAt\":\"2026-09-22T10:00:00Z\",\"tokens\":12,\"costMicros\":24,\"outcome\":\"ok\","
                + "\"action\":null,\"redactedFields\":2,\"rawExposure\":false}", json.writeValueAsString(v));
    }
}
