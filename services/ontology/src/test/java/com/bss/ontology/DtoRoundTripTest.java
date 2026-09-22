package com.bss.ontology;

import com.bss.ontology.dto.ActionCheck;
import com.bss.ontology.dto.Check;
import com.bss.ontology.dto.ComponentDescriptor;
import com.bss.ontology.dto.ConformanceResult;
import com.bss.ontology.dto.CustomerContext;
import com.bss.ontology.dto.CustomerRecommendations;
import com.bss.ontology.dto.ExecuteReceipt;
import com.bss.ontology.dto.Explanation;
import com.bss.ontology.dto.McpMessages;
import com.bss.ontology.dto.PermissionVerdict;
import com.bss.ontology.dto.PolicyVerdict;
import com.bss.ontology.dto.Recommendation;
import com.bss.ontology.dto.RecommendationOutcome;
import com.bss.ontology.dto.Situation;
import com.bss.ontology.dto.SituationSummary;
import com.bss.ontology.dto.UpgradeOption;
import com.bss.ontology.dto.Verdict;
import com.bss.ontology.service.Resolver;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The computed records write the bytes the maps used to write: keys in the
 * same order, absent where the map left the key off, present with null where
 * it put one, numbers as written, the registry's own shapes passed through.
 * Pure Jackson, configured as Spring Boot configures it — no context.
 */
class DtoRoundTripTest {

    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private static Check check(String refusal) {
        return new Check(refusal == null, refusal,
                List.of(new Verdict("subscription-active", "the subscription is active", true, "it is \"active\""),
                        new Verdict("no-commitment", "no commitment binds it", null, null)),
                new PermissionVerdict(true, "self:owner", "the caller is the owner of the subscription", List.of("as the owner — yes")),
                new PolicyVerdict("ordering", "allow", null, null, "no rule in domain \"ordering\" objects"), new Resolver.Resolved());
    }

    @Test
    void verdict_isAWordOnTheWireAndDetailOnlyWhenThereIsOne() throws Exception {
        assertEquals("{\"id\":\"a\",\"says\":\"it holds\",\"verdict\":\"holds\",\"detail\":\"yes\"}",
                json.writeValueAsString(new Verdict("a", "it holds", true, "yes")));
        assertEquals("{\"id\":\"b\",\"says\":\"it fails\",\"verdict\":\"fails\"}", json.writeValueAsString(new Verdict("b", "it fails", false, null)));
        assertEquals("{\"id\":\"c\",\"says\":\"?\",\"verdict\":\"unknown\"}", json.writeValueAsString(new Verdict("c", "?", null, null)));
    }

    @Test
    void permissionAndPolicy_keepTheirShortForms() throws Exception {
        assertEquals("{\"ok\":false,\"says\":\"this is not your subscription\",\"tried\":[\"as the owner — no\"]}",
                json.writeValueAsString(new PermissionVerdict(false, null, "this is not your subscription", List.of("as the owner — no"))));
        assertEquals("{\"decision\":\"none\",\"says\":\"no policy domain applies to this action\"}", json.writeValueAsString(PolicyVerdict.none()));
        assertEquals("{\"domain\":\"ordering\",\"decision\":\"deny\",\"ruleName\":\"r1\",\"message\":\"no\",\"says\":\"rule \\\"r1\\\" refuses: no\"}",
                json.writeValueAsString(new PolicyVerdict("ordering", "deny", "r1", "no", "rule \"r1\" refuses: no")));
    }

    @Test
    void check_hidesTheResolvedObjectsAndTheActionRidesUnwrappedBesideIt() throws Exception {
        String wire = json.writeValueAsString(new ActionCheck("upgradeSubscription", check(null)));
        assertEquals("{\"action\":\"upgradeSubscription\",\"allowed\":true,\"preconditions\":["
                + "{\"id\":\"subscription-active\",\"says\":\"the subscription is active\",\"verdict\":\"holds\",\"detail\":\"it is \\\"active\\\"\"},"
                + "{\"id\":\"no-commitment\",\"says\":\"no commitment binds it\",\"verdict\":\"unknown\"}],"
                + "\"permission\":{\"ok\":true,\"by\":\"self:owner\",\"says\":\"the caller is the owner of the subscription\",\"tried\":[\"as the owner — yes\"]},"
                + "\"policy\":{\"domain\":\"ordering\",\"decision\":\"allow\",\"says\":\"no rule in domain \\\"ordering\\\" objects\"}}", wire);
        JsonNode refused = json.readTree(json.writeValueAsString(check("the line is paused")));
        assertEquals(List.of("allowed", "refusal", "preconditions", "permission", "policy"), keys(refused));
        assertFalse(wire.contains("resolved"));
    }

    @Test
    void executeReceipt_refusedAndDoneWriteWhatEachPathWrote() throws Exception {
        ExecuteReceipt refused = new ExecuteReceipt("upgradeSubscription", check("the line is paused"), null, null, false, null, null, null,
                null, "the line is paused", null, null, null, "Refused: the line is paused.");
        assertEquals(List.of("action", "check", "done", "refusal", "said"), keys(json.readTree(json.writeValueAsString(refused))));
        JsonNode result = json.readTree("{\"id\":\"po-1\",\"state\":\"completed\"}");
        JsonNode effects = json.readTree("[{\"capability\":\"billing.rerate\",\"when\":\"next cycle\"}]");
        ExecuteReceipt done = new ExecuteReceipt("upgradeSubscription", check(null), "the caller",
                new ExecuteReceipt.ExecutedBy("product-ordering", "ordering.create", "POST /productOrderingManagement/v4/productOrder"),
                true, null, result, "ontology-1", null, null, null, effects, json.readTree("[]"), "Done.");
        JsonNode back = json.readTree(json.writeValueAsString(done));
        assertEquals(List.of("action", "check", "executedAs", "executedBy", "done", "result", "decisionId", "effects", "emits", "said"), keys(back));
        assertEquals("{\"component\":\"product-ordering\",\"capability\":\"ordering.create\",\"route\":\"POST /productOrderingManagement/v4/productOrder\"}",
                back.get("executedBy").toString());
        assertEquals(effects, back.get("effects"));
        ExecuteReceipt filed = new ExecuteReceipt("creditNote", check(null), null, null, false, true, null, "ontology-2", "apr-9", null, null,
                null, null, "Filed for approval.");
        assertEquals(List.of("action", "check", "done", "filed", "decisionId", "approvalId", "said"), keys(json.readTree(json.writeValueAsString(filed))));
    }

    @Test
    void explanation_pageHasKnownAndNoTitle_journeyHasSteps() throws Exception {
        Explanation action = Explanation.of("action", "upgradeSubscription", "Upgrade subscription", List.of("one", "two"), "\n");
        assertEquals("{\"kind\":\"action\",\"name\":\"upgradeSubscription\",\"title\":\"Upgrade subscription\",\"text\":\"one\\ntwo\",\"lines\":[\"one\",\"two\"]}",
                json.writeValueAsString(action));
        Explanation page = new Explanation("page", "billing/bills", null, false, "none", List.of("none"), null);
        assertEquals("{\"kind\":\"page\",\"name\":\"billing/bills\",\"known\":false,\"text\":\"none\",\"lines\":[\"none\"]}", json.writeValueAsString(page));
        Explanation journey = action.withSteps(List.of(new Explanation.Step("concept", "Subscription", "a line")));
        assertTrue(json.writeValueAsString(journey).endsWith("\"steps\":[{\"kind\":\"concept\",\"name\":\"Subscription\",\"says\":\"a line\"}]}"));
    }

    @Test
    void conformance_shortAnswersCarryOnlyWhatWasLearned() throws Exception {
        assertEquals("{\"component\":\"nobody\",\"ok\":false,\"says\":\"the registry has no entry for this component\"}",
                json.writeValueAsString(ConformanceResult.unknown("nobody", null, "the registry has no entry for this component")));
        ConformanceResult.Declared declared = new ConformanceResult.Declared(json.readTree("[\"E1\"]"), json.readTree("[]"), json.readTree("[\"c.a\"]"));
        assertEquals("{\"component\":\"x\",\"declared\":{\"events\":[\"E1\"],\"manages\":[],\"capabilities\":[\"c.a\"]},\"ok\":false,\"says\":\"silent\"}",
                json.writeValueAsString(ConformanceResult.unknown("x", declared, "silent")));
        ConformanceResult full = new ConformanceResult("x", declared, new ConformanceResult.Runtime(json.readTree("[\"E1\"]"), json.readTree("[]"), 3),
                true, List.of(), List.of(), List.of("GET /a"), List.of(), "the registry and the running component agree");
        assertEquals(List.of("component", "declared", "runtime", "ok", "missingEvents", "missingRoutes", "servedRoutes", "missingManages", "says"),
                keys(json.readTree(json.writeValueAsString(full))));
    }

    @Test
    void componentDescriptor_pinsTheOrderTheMapHad() throws Exception {
        ComponentDescriptor d = new ComponentDescriptor("ontology", "the registry", List.of(), List.of("ontology.explain"), List.of("DecisionRecordedEvent"),
                "bss.ontology.events", List.of("[GET] /ontology/v1"), new ComponentDescriptor.Invoke("/ontology/v1", "/ontology/v1/mcp",
                        "/ontology/v1/actions/{name}/check", "/ontology/v1/actions/{name}/execute"), "GenAlphaComponent");
        assertEquals("{\"component\":\"ontology\",\"meaning\":\"the registry\",\"manages\":[],\"capabilities\":[\"ontology.explain\"],"
                + "\"events\":[\"DecisionRecordedEvent\"],\"topic\":\"bss.ontology.events\",\"routes\":[\"[GET] /ontology/v1\"],"
                + "\"invoke\":{\"read\":\"/ontology/v1\",\"mcp\":\"/ontology/v1/mcp\",\"check\":\"/ontology/v1/actions/{name}/check\","
                + "\"execute\":\"/ontology/v1/actions/{name}/execute\"},\"@type\":\"GenAlphaComponent\"}", json.writeValueAsString(d));
    }

    @Test
    void customerContext_omitsThePersonWhenUnreadAndUpgradesWhenNotActive() throws Exception {
        CustomerContext.Subscription active = new CustomerContext.Subscription("p1", "Mobile M", "active", "po-m", "2026-01-01", null, null,
                List.of(new UpgradeOption("po-l", "Mobile L", new BigDecimal("349.00"), "Mobile")));
        CustomerContext.Subscription ended = new CustomerContext.Subscription("p2", "Fibre", "terminated", "po-f", "", "Fibre 100", "2026-02-02", null);
        CustomerContext ctx = new CustomerContext("c1", null, List.of(active, ended), List.of(new CustomerContext.ServiceLine("s1", "Mobile M", "active", "+47 900")),
                List.of(new CustomerContext.Bill("b1", "sent", "2026-03-01", "199.00")), List.of(), List.of("upgradeSubscription — move up"),
                List.of("bills (not allowed to read it)"), "CustomerContext");
        JsonNode back = json.readTree(json.writeValueAsString(ctx));
        assertEquals(List.of("customerId", "subscriptions", "services", "bills", "receipts", "actionsAvailable", "unanswered", "@type"), keys(back));
        assertEquals("{\"id\":\"p1\",\"name\":\"Mobile M\",\"status\":\"active\",\"offeringId\":\"po-m\",\"since\":\"2026-01-01\","
                + "\"availableUpgrades\":[{\"id\":\"po-l\",\"name\":\"Mobile L\",\"monthly\":349.00,\"family\":\"Mobile\"}]}", json.writeValueAsString(active));
        assertEquals("{\"id\":\"p2\",\"name\":\"Fibre\",\"status\":\"terminated\",\"offeringId\":\"po-f\",\"since\":\"\",\"previousOffering\":\"Fibre 100\","
                + "\"offeringChangedAt\":\"2026-02-02\"}", back.get("subscriptions").get(1).toString());
        assertEquals("\"199.00\"", back.get("bills").get(0).get("amountDue").toString());
    }

    @Test
    void situation_eachKindCarriesOnlyItsOwnKeys() throws Exception {
        assertEquals(List.of("kind", "id", "says", "since", "severity", "actionRequired"), keys(json.readTree(json.writeValueAsString(Situation.incident("i1", "x", "t")))));
        assertEquals(List.of("kind", "id", "name", "says", "severity", "actionRequired"), keys(json.readTree(json.writeValueAsString(Situation.paused("s1", "Mobile", "x")))));
        assertEquals(List.of("kind", "id", "amount", "currency", "dueAt", "says", "severity", "actionRequired"),
                keys(json.readTree(json.writeValueAsString(Situation.arranged("c1", "100", "NOK", "t", "x")))));
        assertEquals(List.of("kind", "id", "amount", "currency", "step", "says", "severity", "actionRequired"),
                keys(json.readTree(json.writeValueAsString(Situation.overdue("c1", "100", "NOK", 2, "x")))));
        assertEquals("{\"kind\":\"bill\",\"id\":\"b1\",\"amount\":\"199.00\",\"says\":\"x\",\"severity\":\"info\",\"actionRequired\":false}",
                json.writeValueAsString(Situation.bill("b1", "199.00", "x")));
        SituationSummary summary = new SituationSummary("paused", "warning", true, 2, List.of("s1", "s2"), "2 services are paused", null, null, null, "Mobile");
        assertEquals("{\"kind\":\"paused\",\"severity\":\"warning\",\"actionRequired\":true,\"count\":2,\"members\":[\"s1\",\"s2\"],"
                + "\"says\":\"2 services are paused\",\"name\":\"Mobile\"}", json.writeValueAsString(summary));
    }

    @Test
    void recommendation_growsInStagesAndDropsWhatAKindDoesNotHave() throws Exception {
        Recommendation offer = Recommendation.offer("Mobile L", "po-l", 5, "why", "an offer to consider")
                .ranked(new Recommendation.Ranking(5, 0, 0, 0, 0, "no history on this desk yet"), 5).decided("rec-1");
        JsonNode back = json.readTree(json.writeValueAsString(offer));
        assertEquals(List.of("kind", "action", "title", "inputs", "offeringId", "priority", "allowed", "why", "meaning", "ranking", "rank", "decisionId"), keys(back));
        assertEquals("{\"priority\":5,\"shown\":0,\"accepted\":0,\"dismissed\":0,\"adjustment\":0,\"says\":\"no history on this desk yet\"}", back.get("ranking").toString());
        Recommendation explain = Recommendation.explain("explainBill", "Walk through the open bill", "why", 3);
        assertEquals("{\"kind\":\"explain\",\"action\":\"explainBill\",\"title\":\"Walk through the open bill\",\"priority\":3,\"allowed\":true,\"why\":\"why\"}",
                json.writeValueAsString(explain));
        Recommendation action = Recommendation.action("resumeSubscription", "Resume subscription", Map.of("serviceId", "s1"), 2, check(null),
                "the line is paused; every condition holds for this caller", "resume it", "supervised");
        assertEquals(List.of("kind", "action", "title", "inputs", "priority", "allowed", "check", "why", "meaning", "autonomy"),
                keys(json.readTree(json.writeValueAsString(action))));
        assertEquals("resumeSubscription||the line is paused; every condition holds for this caller", action.dedupeKey());
        CustomerRecommendations all = new CustomerRecommendations("c1", List.of(), List.of(), "none", true, List.of(), List.of(), List.of(), "Nothing stands out.",
                "CustomerRecommendations");
        assertEquals(List.of("customerId", "situation", "summary", "severity", "healthy", "recommendations", "receipts", "unanswered", "said", "@type"),
                keys(json.readTree(json.writeValueAsString(all))));
    }

    @Test
    void outcome_requestIsLenientAndTheReplyIsFixed() throws Exception {
        RecommendationOutcome.Request r = json.readValue("{\"outcome\":\"helpful\",\"reason\":null,\"stray\":1}", RecommendationOutcome.Request.class);
        assertEquals("helpful", r.outcome());
        assertNull(r.reason());
        assertEquals("", json.readValue("{}", RecommendationOutcome.Request.class).outcomeOrEmpty());
        assertEquals("{\"decisionId\":\"rec-1\",\"outcome\":\"helpful\",\"said\":\"Noted.\",\"@type\":\"RecommendationOutcome\"}",
                json.writeValueAsString(new RecommendationOutcome("rec-1", "helpful", "Noted.", "RecommendationOutcome")));
    }

    @Test
    void mcp_toolSchemaAndEnvelopeAndStructuredContent() throws Exception {
        Map<String, McpMessages.Property> props = new LinkedHashMap<>();
        props.put("kind", new McpMessages.Property("string", null, List.of("concept", "action")));
        props.put("name", McpMessages.Property.string("what"));
        McpMessages.Tool tool = new McpMessages.Tool("explain", "Explain.", new McpMessages.InputSchema("object", props, List.of("kind", "name")));
        assertEquals("{\"name\":\"explain\",\"description\":\"Explain.\",\"inputSchema\":{\"type\":\"object\",\"properties\":{"
                + "\"kind\":{\"type\":\"string\",\"enum\":[\"concept\",\"action\"]},\"name\":{\"type\":\"string\",\"description\":\"what\"}},"
                + "\"required\":[\"kind\",\"name\"]}}", json.writeValueAsString(tool));
        assertEquals("{\"type\":\"object\",\"properties\":{}}", json.writeValueAsString(new McpMessages.InputSchema("object", Map.of(), null)));
        assertEquals("{\"jsonrpc\":\"2.0\",\"id\":null,\"result\":{}}", json.writeValueAsString(new McpMessages.Reply("2.0", null, Map.of())));
        assertEquals("{\"jsonrpc\":\"2.0\",\"id\":7,\"error\":{\"code\":-32601,\"message\":\"method not found: x\"}}",
                json.writeValueAsString(new McpMessages.ErrorReply("2.0", json.readTree("7"), new McpMessages.ErrorReply.Error(-32601, "method not found: x"))));
        Check c = check(null);
        assertSame(c, McpMessages.structured(c));
        assertEquals(Map.of("value", "x"), McpMessages.structured("x"));
        McpMessages.ToolResult result = new McpMessages.ToolResult(List.of(new McpMessages.ToolResult.Content("text", "{}")), c, false);
        assertEquals(List.of("content", "structuredContent", "isError"), keys(json.readTree(json.writeValueAsString(result))));
    }

    private static List<String> keys(JsonNode node) {
        List<String> out = new java.util.ArrayList<>();
        node.fieldNames().forEachRemaining(out::add);
        return out;
    }
}
