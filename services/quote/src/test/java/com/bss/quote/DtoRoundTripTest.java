package com.bss.quote;

import com.bss.quote.dto.ActivityLog;
import com.bss.quote.dto.ActivityView;
import com.bss.quote.dto.ConfigRuleView;
import com.bss.quote.dto.ConfigurationCheck;
import com.bss.quote.dto.ConfigurationCheck.RuleViolation;
import com.bss.quote.dto.EntityRef;
import com.bss.quote.dto.FunnelReport;
import com.bss.quote.dto.GuidedSelling;
import com.bss.quote.dto.HandoffBodies;
import com.bss.quote.dto.LeadRules;
import com.bss.quote.dto.LeadSignal;
import com.bss.quote.dto.LeadView;
import com.bss.quote.dto.LineItem;
import com.bss.quote.dto.Money;
import com.bss.quote.dto.OpenTasks;
import com.bss.quote.dto.OpportunityItemView;
import com.bss.quote.dto.OpportunityView;
import com.bss.quote.dto.OwnerRef;
import com.bss.quote.dto.PipelineBoard;
import com.bss.quote.dto.PricingRuleView;
import com.bss.quote.dto.QuotaAttainment;
import com.bss.quote.dto.QuotaView;
import com.bss.quote.dto.QuoteItem;
import com.bss.quote.dto.QuoteRequests;
import com.bss.quote.dto.QuoteView;
import com.bss.quote.dto.RelatedPartyRef;
import com.bss.quote.dto.SalesReceipts;
import com.bss.quote.dto.SalesRequests;
import com.bss.quote.dto.SnapshotView;
import com.bss.quote.dto.WonReport;
import com.bss.quote.entity.OpportunityActivity;
import com.bss.quote.entity.OpportunityItem;
import com.bss.quote.entity.Quote;
import com.bss.quote.entity.SalesLead;
import com.bss.quote.entity.SalesOpportunity;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The wire records write the bytes the maps used to write: keys in the
 * same order, money with the scale the entity stores, absent keys absent.
 * Pure Jackson, configured as Spring Boot configures it — no context, no
 * database. Every factory gets an exact-bytes assertion: positional record
 * constructors are where a slip lands now.
 */
class DtoRoundTripTest {

    private final ObjectMapper json = new ObjectMapper()
            .registerModule(new JavaTimeModule()).registerModule(new Jdk8Module())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private static final OffsetDateTime AT = OffsetDateTime.parse("2026-09-22T09:08:12.169178Z");

    // ---------------- shared atoms ----------------

    @Test
    void money_keepsTheScaleItWasGiven() throws Exception {
        assertEquals("{\"value\":5890.00,\"unit\":\"EUR\",\"period\":\"month\"}",
                json.writeValueAsString(Money.monthly(new BigDecimal("5890.00"), "EUR")));
        assertEquals("{\"value\":4900,\"unit\":\"EUR\",\"period\":\"month\"}",
                json.writeValueAsString(new Money(new BigDecimal("4900"), "EUR", "month")));
        assertEquals("{\"value\":0,\"unit\":\"USD\",\"period\":\"oneTime\"}",
                json.writeValueAsString(Money.oneTime(BigDecimal.ZERO, "USD")));
        // a stored line's money reads back with the stored scale, never through a double
        Money stored = json.readValue("{\"period\":\"month\",\"value\":90.000000,\"unit\":\"USD\"}", Money.class);
        assertEquals(new BigDecimal("90.000000"), stored.value());
        assertEquals("{\"value\":90.000000,\"unit\":\"USD\",\"period\":\"month\"}", json.writeValueAsString(stored));
    }

    @Test
    void entityRef_writesOnlyWhatItHas_andRoundTripsExtensions() throws Exception {
        assertEquals("{\"id\":\"i1\"}", json.writeValueAsString(EntityRef.of("i1")));
        assertEquals("{\"id\":\"off-1\",\"name\":\"Fibre 300\"}", json.writeValueAsString(EntityRef.of("off-1", "Fibre 300")));
        assertEquals("{\"id\":\"a1\",\"href\":\"/tmf-api/agreementManagement/v4/agreement/a1\"}",
                json.writeValueAsString(EntityRef.at("a1", "/tmf-api/agreementManagement/v4/agreement/a1")));
        EntityRef back = json.readValue("{\"name\":\"X\",\"id\":\"1\",\"@referredType\":\"ProductOffering\"}", EntityRef.class);
        assertEquals("1", back.id());
        assertEquals("ProductOffering", back.extensions().get("@referredType"));
        assertEquals("{\"id\":\"1\",\"name\":\"X\",\"@referredType\":\"ProductOffering\"}", json.writeValueAsString(back));
        assertEquals("{\"id\":\"p1\",\"role\":\"customer\"}", json.writeValueAsString(RelatedPartyRef.customer("p1")));
        assertEquals("{\"name\":\"Ada\"}", json.writeValueAsString(new OwnerRef(null, "Ada")));
        assertNull(OwnerRef.ofNullable(null, null));
    }

    // ---------------- TMF648 quote ----------------

    @Test
    void quoteItem_intentLine_andStoredBlobRoundTrip() throws Exception {
        JsonNode included = json.readTree("{\"value\":50.0,\"units\":\"Mtokens\"}");
        JsonNode overage = json.readTree("{\"value\":4.0,\"unit\":\"EUR\"}");
        QuoteItem line = QuoteItem.proposed(EntityRef.of("off-ai", "Edge AI Inferencing"), "GPU next door",
                new Money(new BigDecimal("990"), "EUR", "month"),
                new QuoteItem.Allowance("AI inference tokens", included, overage));
        String bytes = json.writeValueAsString(line);
        assertEquals("{\"offering\":{\"id\":\"off-ai\",\"name\":\"Edge AI Inferencing\"},\"reason\":\"GPU next door\","
                + "\"unitPrice\":{\"value\":990,\"unit\":\"EUR\",\"period\":\"month\"},"
                + "\"allowance\":{\"usageType\":\"AI inference tokens\",\"included\":{\"value\":50.0,\"units\":\"Mtokens\"},"
                + "\"overagePrice\":{\"value\":4.0,\"unit\":\"EUR\"}}}", bytes);
        // the stored blob (written by an older JVM with Map.of's own key order) parses into the record
        List<QuoteItem> stored = json.readValue("[{\"offering\":{\"name\":\"Stadium 5G Slice\",\"id\":\"s1\"},"
                + "\"reason\":\"500 Mbps\",\"unitPrice\":{\"unit\":\"EUR\",\"period\":\"month\",\"value\":4900},"
                + "\"houseKey\":\"kept\"}]", new TypeReference<List<QuoteItem>>() { });
        assertEquals(1, stored.size());
        assertEquals("s1", stored.get(0).offering().id());
        assertEquals(new BigDecimal("4900"), stored.get(0).unitPrice().value());
        assertEquals("kept", stored.get(0).extensions().get("houseKey"));
        assertEquals("{\"offering\":{\"id\":\"s1\",\"name\":\"Stadium 5G Slice\"},\"reason\":\"500 Mbps\","
                + "\"unitPrice\":{\"value\":4900,\"unit\":\"EUR\",\"period\":\"month\"},\"houseKey\":\"kept\"}",
                json.writeValueAsString(stored.get(0)));
    }

    @Test
    void quoteItem_cpqLines_writeTheDiscountThatWon() throws Exception {
        EntityRef off = EntityRef.of("", "SEGOFF");
        Money unit = Money.monthly(new BigDecimal("90.000000"), "USD");
        assertEquals("{\"offering\":{\"id\":\"\",\"name\":\"SEGOFF\"},\"quantity\":1,"
                + "\"unitPrice\":{\"value\":90.000000,\"unit\":\"USD\",\"period\":\"month\"},\"listUnitPrice\":100.00,"
                + "\"volumeDiscountPercent\":10.00,\"pricedBy\":\"volume\",\"recurring\":true}",
                json.writeValueAsString(QuoteItem.byVolume(off, 1, unit, new BigDecimal("100.00"),
                        new BigDecimal("10.00"), true)));
        assertEquals("{\"offering\":{\"id\":\"\",\"name\":\"SEGOFF\"},\"quantity\":2,"
                + "\"unitPrice\":{\"value\":70.000000,\"unit\":\"USD\",\"period\":\"month\"},\"listUnitPrice\":100.00,"
                + "\"segmentDiscountPercent\":30.00,\"pricedBy\":\"segment:enterprise\",\"recurring\":true}",
                json.writeValueAsString(QuoteItem.bySegment(off, 2, Money.monthly(new BigDecimal("70.000000"), "USD"),
                        new BigDecimal("100.00"), new BigDecimal("30.00"), "enterprise", true)));
        assertEquals("{\"offering\":{\"id\":\"o1\",\"name\":\"Router\"},\"quantity\":3,"
                + "\"unitPrice\":{\"value\":49.90,\"unit\":\"NOK\",\"period\":\"oneTime\"},\"recurring\":false}",
                json.writeValueAsString(QuoteItem.priced(EntityRef.of("o1", "Router"), 3,
                        Money.oneTime(new BigDecimal("49.90"), "NOK"), false)));
    }

    @Test
    void quoteView_writesTheTmf648ShapeInOrder_optionalsOffUntilTheyExist() throws Exception {
        Quote q = new Quote();
        q.setId("q1");
        q.setHref("/tmf-api/quoteManagement/v4/quote/q1");
        q.setDescription("Tournament slice");
        q.setState("inProgress");
        q.setIntentId("i1");
        q.setOwnerPartyId("stadium");
        q.setMonthlyTotal(new BigDecimal("5890.00"));
        q.setCurrency("EUR");
        q.setNarrative("Connectivity plus metered edge AI.");
        List<QuoteItem> items = List.of(QuoteItem.proposed(EntityRef.of("s1", "Stadium 5G Slice"), "500 Mbps",
                new Money(new BigDecimal("4900"), "EUR", "month"), null));
        assertEquals("{\"id\":\"q1\",\"href\":\"/tmf-api/quoteManagement/v4/quote/q1\",\"description\":\"Tournament slice\","
                + "\"state\":\"inProgress\",\"intent\":{\"id\":\"i1\"},\"relatedParty\":[{\"id\":\"stadium\",\"role\":\"customer\"}],"
                + "\"quoteItem\":[{\"offering\":{\"id\":\"s1\",\"name\":\"Stadium 5G Slice\"},\"reason\":\"500 Mbps\","
                + "\"unitPrice\":{\"value\":4900,\"unit\":\"EUR\",\"period\":\"month\"}}],"
                + "\"quoteTotalPrice\":{\"value\":5890.00,\"unit\":\"EUR\",\"period\":\"month\"},"
                + "\"approvalStatus\":\"notRequired\",\"narrative\":\"Connectivity plus metered edge AI.\","
                + "\"signatureStatus\":\"unsigned\",\"@type\":\"Quote\"}",
                json.writeValueAsString(QuoteView.of(q, items)));

        // accepted, discounted, one-time total, signed: every optional appears in its slot
        q.setState("accepted");
        q.setOneTimeTotal(new BigDecimal("250.00"));
        q.setDiscountPercent(new BigDecimal("25.00"));
        q.setApprovalStatus("approved");
        q.setProductOrderId("po-1");
        q.setAgreementId("agr-1");
        q.setSignatureStatus("signed");
        q.setSignedBy("Ada");
        q.setSignedAt(AT);
        String bytes = json.writeValueAsString(QuoteView.of(q, List.of()));
        assertEquals("{\"id\":\"q1\",\"href\":\"/tmf-api/quoteManagement/v4/quote/q1\",\"description\":\"Tournament slice\","
                + "\"state\":\"accepted\",\"intent\":{\"id\":\"i1\"},\"relatedParty\":[{\"id\":\"stadium\",\"role\":\"customer\"}],"
                + "\"quoteItem\":[],\"quoteTotalPrice\":{\"value\":5890.00,\"unit\":\"EUR\",\"period\":\"month\"},"
                + "\"quoteOneTimePrice\":{\"value\":250.00,\"unit\":\"EUR\",\"period\":\"oneTime\"},"
                + "\"discountPercent\":25.00,\"netMonthlyTotal\":4417.500000,\"approvalStatus\":\"approved\","
                + "\"narrative\":\"Connectivity plus metered edge AI.\",\"productOrder\":{\"id\":\"po-1\"},"
                + "\"agreement\":{\"id\":\"agr-1\",\"href\":\"/tmf-api/agreementManagement/v4/agreement/agr-1\"},"
                + "\"signatureStatus\":\"signed\",\"signedBy\":\"Ada\",\"signedAt\":\"2026-09-22T09:08:12.169178Z\","
                + "\"@type\":\"Quote\"}", bytes);
    }

    @Test
    void cpqRules_verdicts_andGuidedSelling() throws Exception {
        assertEquals("{\"valid\":true,\"violations\":[]}", json.writeValueAsString(ConfigurationCheck.of(List.of())));
        assertEquals("{\"valid\":false,\"violations\":[{\"ruleType\":\"requires\",\"subject\":\"IP\",\"object\":\"Line\","
                + "\"message\":\"IP requires Line\"},{\"ruleType\":\"minQty\",\"subject\":\"Seat\",\"message\":\"Seat needs 5\"}]}",
                json.writeValueAsString(ConfigurationCheck.of(List.of(
                        new RuleViolation("requires", "IP", "Line", "IP requires Line"),
                        new RuleViolation("minQty", "Seat", null, "Seat needs 5")))));
        assertEquals("{\"id\":\"r1\",\"ruleType\":\"maxQty\",\"subjectOffering\":\"Seat\",\"qty\":10,\"message\":\"Seat allows at most 10\"}",
                json.writeValueAsString(new ConfigRuleView("r1", "maxQty", "Seat", null, 10, "Seat allows at most 10")));
        assertEquals("{\"id\":\"p1\",\"offeringName\":\"Fibre\",\"minQuantity\":10,\"discountPercent\":25.00}",
                json.writeValueAsString(new PricingRuleView("p1", "Fibre", 10, null, new BigDecimal("25.00"))));
        assertEquals("{\"id\":\"p2\",\"offeringName\":\"Fibre\",\"minQuantity\":1,\"segment\":\"enterprise\",\"discountPercent\":30.00}",
                json.writeValueAsString(new PricingRuleView("p2", "Fibre", 1, "enterprise", new BigDecimal("30.00"))));
        assertEquals("{\"id\":\"g1\",\"questionKey\":\"sites\",\"prompt\":\"How many sites?\",\"sortOrder\":1}",
                json.writeValueAsString(new GuidedSelling.QuestionView("g1", "sites", "How many sites?", 1)));
        assertEquals("{\"id\":\"g2\",\"questionKey\":\"sites\",\"answerValue\":\"multi\",\"offeringName\":\"SD-WAN\",\"quantity\":2}",
                json.writeValueAsString(new GuidedSelling.RecommendationRuleView("g2", "sites", "multi", "SD-WAN", 2)));
        assertEquals("{\"recommendations\":[{\"offeringName\":\"SD-WAN\",\"quantity\":2,\"because\":\"sites=multi\"}]}",
                json.writeValueAsString(new GuidedSelling.Recommendations(List.of(
                        new GuidedSelling.Recommendation("SD-WAN", 2, "sites=multi")))));
    }

    @Test
    void quoteRequests_parseLeniently_andIgnoreWhatTheyDoNotDeclare() throws Exception {
        QuoteRequests.QuotePatch p = json.readValue("{\"discountPercent\":\"25\",\"state\":\"approved\",\"tenantId\":\"x\"}",
                QuoteRequests.QuotePatch.class);
        assertEquals(new BigDecimal("25"), p.discountPercent());
        assertEquals("approved", p.state());
        QuoteRequests.ValidateRequest v = json.readValue("{}", QuoteRequests.ValidateRequest.class);
        assertTrue(v.items().isEmpty());
        LineItem li = json.readValue("{\"offeringName\":\"IP\",\"quantity\":\"3\",\"unitPrice\":49.90,\"recurring\":false}", LineItem.class);
        assertEquals(3, li.quantityOrOne());
        assertEquals(new BigDecimal("49.90"), li.unitPriceOrZero());
        assertFalse(li.isRecurring());
        LineItem bare = json.readValue("{\"offeringName\":\"IP\"}", LineItem.class);
        assertEquals(1, bare.quantityOrOne());
        assertEquals(BigDecimal.ZERO, bare.unitPriceOrZero());
        assertTrue(bare.isRecurring());
        LeadSignal sig = json.readValue("{\"email\":\"a@b\",\"knownProspect\":true,\"engagement\":\"clicked\",\"engaged\":true}",
                LeadSignal.class);
        assertEquals("clicked", sig.engagement());
        assertFalse(sig.absent());
        assertTrue(LeadSignal.NONE.absent());
    }

    @Test
    void handoffBodies_writeTheStandardShapes() throws Exception {
        assertEquals("{\"productOrderItem\":[{\"action\":\"add\",\"productOffering\":{\"id\":\"o1\",\"name\":\"Fibre\"}}],"
                + "\"relatedParty\":[{\"id\":\"p1\",\"role\":\"customer\"}]}",
                json.writeValueAsString(new HandoffBodies.ProductOrderRequest(
                        List.of(HandoffBodies.OrderItem.add(EntityRef.of("o1", "Fibre"))),
                        List.of(RelatedPartyRef.customer("p1")))));
        assertEquals("{\"name\":\"Agreement — Q\",\"agreementType\":\"commercial\",\"status\":\"active\","
                + "\"engagedParty\":[{\"id\":\"p1\",\"role\":\"customer\"}],"
                + "\"agreementItem\":[{\"productOffering\":{\"id\":\"o1\",\"name\":\"Fibre\"}}],"
                + "\"characteristic\":[{\"name\":\"quoteId\",\"value\":\"q1\"}]}",
                json.writeValueAsString(new HandoffBodies.AgreementRequest("Agreement — Q", "commercial", "active",
                        List.of(RelatedPartyRef.customer("p1")),
                        List.of(new HandoffBodies.AgreementItem(EntityRef.of("o1", "Fibre"))),
                        List.of(new HandoffBodies.NameValue("quoteId", "q1")))));
        assertEquals("{\"description\":\"Q\",\"items\":[],\"monthlyTotal\":100,\"currency\":\"EUR\"}",
                json.writeValueAsString(new HandoffBodies.NarrativeContext("Q", List.of(), new BigDecimal("100"), "EUR")));
    }

    // ---------------- TMF699 sales ----------------

    @Test
    void leadView_writesTheTmf699ShapeInOrder() throws Exception {
        SalesLead lead = new SalesLead();
        lead.setId("l1");
        lead.setHref("/tmf-api/salesManagement/v4/salesLead/l1");
        lead.setName("Office fiber for 12 people");
        lead.setContactName("Lea");
        lead.setContactEmail("lea@ads.example");
        lead.setCompany("AdCo");
        lead.setSource("social");
        lead.setState("acknowledged");
        lead.setGrade("cold");
        lead.setCreatedAt(AT);
        lead.setLastUpdate(AT);
        assertEquals("{\"id\":\"l1\",\"href\":\"/tmf-api/salesManagement/v4/salesLead/l1\",\"name\":\"Office fiber for 12 people\","
                + "\"contactName\":\"Lea\",\"contactEmail\":\"lea@ads.example\",\"company\":\"AdCo\",\"source\":\"social\","
                + "\"state\":\"acknowledged\",\"score\":0,\"grade\":\"cold\",\"creationDate\":\"2026-09-22T09:08:12.169178Z\","
                + "\"lastUpdate\":\"2026-09-22T09:08:12.169178Z\",\"@type\":\"SalesLead\"}",
                json.writeValueAsString(LeadView.of(lead)));
        lead.setDescription("Needs 12 seats");
        lead.setScore(85);
        lead.setGrade("hot");
        lead.setOwnerName("Ada");
        lead.setCompanySize(12);
        lead.setState("qualified");
        lead.setOpportunityId("o1");
        assertEquals("{\"id\":\"l1\",\"href\":\"/tmf-api/salesManagement/v4/salesLead/l1\",\"name\":\"Office fiber for 12 people\","
                + "\"description\":\"Needs 12 seats\",\"contactName\":\"Lea\",\"contactEmail\":\"lea@ads.example\",\"company\":\"AdCo\","
                + "\"source\":\"social\",\"state\":\"qualified\",\"score\":85,\"grade\":\"hot\",\"owner\":{\"name\":\"Ada\"},"
                + "\"companySize\":12,\"salesOpportunity\":{\"id\":\"o1\",\"href\":\"/tmf-api/salesManagement/v4/salesOpportunity/o1\"},"
                + "\"creationDate\":\"2026-09-22T09:08:12.169178Z\",\"lastUpdate\":\"2026-09-22T09:08:12.169178Z\",\"@type\":\"SalesLead\"}",
                json.writeValueAsString(LeadView.of(lead)));
    }

    @Test
    void opportunityView_writesTheDealInOrder_linesAndLogOnlyWhenPresent() throws Exception {
        SalesOpportunity opp = new SalesOpportunity();
        opp.setId("o1");
        opp.setHref("/tmf-api/salesManagement/v4/salesOpportunity/o1");
        opp.setName("Seg");
        opp.setLeadId("l1");
        opp.setState("developed");
        opp.setStage("qualification");
        opp.setForecastCategory("pipeline");
        opp.setProbability(10);
        opp.setAmount(new BigDecimal("100.00"));
        opp.setCurrency("USD");
        opp.setPartyId("p1");
        opp.setQuoteRef("q1");
        opp.setCreatedAt(AT);
        opp.setLastUpdate(AT);
        OpportunityItem line = new OpportunityItem();
        line.setId("i1");
        line.setOfferingName("SEGOFF");
        line.setQuantity(2);
        line.setUnitPrice(new BigDecimal("50.00"));
        line.setCurrency("USD");
        OpportunityActivity act = new OpportunityActivity();
        act.setId("a1");
        act.setActivityType("lifecycle");
        act.setNote("Qualified from lead");
        act.setOccurredAt(AT);
        act.setStatus("done");
        String bytes = json.writeValueAsString(OpportunityView.of(opp,
                List.of(OpportunityItemView.of(line)), List.of(ActivityView.of(act))));
        assertEquals("{\"id\":\"o1\",\"href\":\"/tmf-api/salesManagement/v4/salesOpportunity/o1\",\"name\":\"Seg\","
                + "\"salesLead\":{\"id\":\"l1\",\"href\":\"/tmf-api/salesManagement/v4/salesLead/l1\"},\"state\":\"developed\","
                + "\"stage\":\"qualification\",\"forecastCategory\":\"pipeline\",\"probability\":10,\"amount\":100.00,"
                + "\"currency\":\"USD\",\"partyId\":\"p1\",\"quote\":{\"id\":\"q1\",\"href\":\"/tmf-api/quoteManagement/v4/quote/q1\"},"
                + "\"items\":[{\"id\":\"i1\",\"offeringName\":\"SEGOFF\",\"quantity\":2,\"unitPrice\":50.00,\"lineTotal\":100.00,\"currency\":\"USD\"}],"
                + "\"activities\":[{\"id\":\"a1\",\"type\":\"lifecycle\",\"note\":\"Qualified from lead\","
                + "\"occurredAt\":\"2026-09-22T09:08:12.169178Z\",\"status\":\"done\"}],"
                + "\"creationDate\":\"2026-09-22T09:08:12.169178Z\",\"lastUpdate\":\"2026-09-22T09:08:12.169178Z\",\"@type\":\"SalesOpportunity\"}",
                bytes);
        // stage clock present → daysInStage rides beside it; empty lines/log are left off; owner and close reason appear
        opp.setStageChangedAt(OffsetDateTime.now().minusDays(3));
        opp.setOwnerId("u1");
        opp.setOwnerName("Ada");
        opp.setExpectedCloseDate(LocalDate.parse("2026-10-01"));
        opp.setState("won");
        opp.setStage("closedWon");
        opp.setCloseReason("signed");
        JsonNode tree = json.valueToTree(OpportunityView.of(opp, List.of(), List.of()));
        assertEquals(3, tree.get("daysInStage").asLong());
        assertEquals("{\"id\":\"u1\",\"name\":\"Ada\"}", tree.get("owner").toString());
        assertEquals("2026-10-01", tree.get("expectedCloseDate").asText());
        assertEquals("signed", tree.get("closeReason").asText());
        assertFalse(tree.has("items"));
        assertFalse(tree.has("activities"));
        assertFalse(tree.has("description"));
    }

    @Test
    void tasks_unwrapTheActivityFirst_thenTheDealAndOverdue() throws Exception {
        OpportunityActivity a = new OpportunityActivity();
        a.setId("a1");
        a.setOpportunityId("o1");
        a.setActivityType("nextStep");
        a.setNote("Call back");
        a.setOccurredAt(AT);
        a.setStatus("open");
        a.setDueDate(AT.plusDays(1));
        a.setAssignee("Ada");
        assertEquals("{\"openCount\":1,\"tasks\":[{\"id\":\"a1\",\"type\":\"nextStep\",\"note\":\"Call back\","
                + "\"occurredAt\":\"2026-09-22T09:08:12.169178Z\",\"status\":\"open\",\"dueDate\":\"2026-09-23T09:08:12.169178Z\","
                + "\"assignee\":\"Ada\",\"opportunityId\":\"o1\",\"overdue\":true}]}",
                json.writeValueAsString(OpenTasks.of(List.of(OpenTasks.TaskView.of(a, AT.plusDays(2))))));
        assertEquals("{\"openCount\":0,\"tasks\":[]}", json.writeValueAsString(OpenTasks.of(List.of())));
        assertEquals("{\"opportunityId\":\"o1\",\"activities\":[{\"id\":\"a1\",\"type\":\"nextStep\",\"note\":\"Call back\","
                + "\"occurredAt\":\"2026-09-22T09:08:12.169178Z\",\"status\":\"open\",\"dueDate\":\"2026-09-23T09:08:12.169178Z\","
                + "\"assignee\":\"Ada\"}]}",
                json.writeValueAsString(new ActivityLog("o1", List.of(ActivityView.of(a)))));
    }

    @Test
    void rulesQuotasAndReports_writeInOrder() throws Exception {
        assertEquals("{\"id\":\"s1\",\"field\":\"companyPresent\",\"points\":20}",
                json.writeValueAsString(new LeadRules.ScoringRuleView("s1", "companyPresent", null, 20)));
        assertEquals("{\"id\":\"s2\",\"field\":\"source\",\"value\":\"campaign\",\"points\":30}",
                json.writeValueAsString(new LeadRules.ScoringRuleView("s2", "source", "campaign", 30)));
        assertEquals("{\"id\":\"r1\",\"minScore\":70,\"assignee\":\"Ada\"}",
                json.writeValueAsString(new LeadRules.RoutingRuleView("r1", 70, "Ada")));
        assertEquals("{\"id\":\"q1\",\"ownerName\":\"Ada\",\"quotaPeriod\":\"2026-09\",\"amount\":5000.00,\"team\":\"North\"}",
                json.writeValueAsString(new QuotaView("q1", "Ada", "2026-09", new BigDecimal("5000.00"), "North")));
        assertEquals("{\"period\":\"2026-09\",\"owners\":[{\"owner\":\"Ada\",\"quota\":5000.00,\"won\":3000.00,\"weightedOpen\":0,"
                + "\"attainmentPct\":60.0,\"coveragePct\":60.0}],\"byTeam\":[{\"team\":\"(no team)\",\"quota\":5000.00,\"won\":3000.00,"
                + "\"weightedOpen\":0,\"attainmentPct\":60.0,\"coveragePct\":60.0}]}",
                json.writeValueAsString(new QuotaAttainment("2026-09",
                        List.of(new QuotaAttainment.OwnerRow("Ada", null, new BigDecimal("5000.00"), new BigDecimal("3000.00"),
                                BigDecimal.ZERO, 60.0, 60.0)),
                        List.of(new QuotaAttainment.TeamRow("(no team)", new BigDecimal("5000.00"), new BigDecimal("3000.00"),
                                BigDecimal.ZERO, 60.0, 60.0)))));
        assertEquals("{\"id\":\"n1\",\"capturedAt\":\"2026-09-22T09:08:12.169178Z\",\"openCount\":65,\"openAmount\":84400.00,"
                + "\"weightedForecast\":47090.00,\"currency\":\"USD\"}",
                json.writeValueAsString(new SnapshotView("n1", AT, 65, new BigDecimal("84400.00"), new BigDecimal("47090.00"), "USD")));
        assertEquals("{\"stages\":[{\"stage\":\"qualification\",\"count\":1,\"amount\":0,\"weighted\":0}],"
                + "\"byCategory\":[{\"category\":\"pipeline\",\"count\":1,\"amount\":0}],\"openCount\":1,\"openAmount\":0,"
                + "\"weightedForecast\":0,\"currency\":\"USD\"}",
                json.writeValueAsString(new PipelineBoard(
                        List.of(new PipelineBoard.StageColumn("qualification", 1, BigDecimal.ZERO, BigDecimal.ZERO)),
                        List.of(new PipelineBoard.CategoryColumn("pipeline", 1, BigDecimal.ZERO)),
                        1, BigDecimal.ZERO, BigDecimal.ZERO, "USD")));
        assertEquals("{\"stageConversion\":[{\"from\":\"qualification\",\"to\":\"needsAnalysis\",\"reachedFrom\":98,\"reachedTo\":5,"
                + "\"conversionPct\":5.1}],\"timeInStage\":[{\"stage\":\"qualification\",\"avgDays\":0.0}],\"winRatePct\":92.6,"
                + "\"wonCount\":50,\"lostCount\":4,\"avgCycleDays\":0.0,\"summary\":\"Win rate 92.6% …\"}",
                json.writeValueAsString(new FunnelReport(
                        List.of(new FunnelReport.StageConversion("qualification", "needsAnalysis", 98, 5, 5.1)),
                        List.of(new FunnelReport.TimeInStage("qualification", 0.0)),
                        92.6, 50, 4, 0.0, "Win rate 92.6% …")));
        assertEquals("{\"wonCount\":50,\"wonAmount\":44200.00,\"bySource\":[{\"source\":\"campaign\",\"wonCount\":27,\"wonAmount\":44200.00}],"
                + "\"currency\":\"USD\"}",
                json.writeValueAsString(new WonReport(50, new BigDecimal("44200.00"),
                        List.of(new WonReport.SourceRow("campaign", 27, new BigDecimal("44200.00"))), "USD")));
        assertEquals("{\"form\":\"form-1\",\"entries\":3,\"imported\":1}",
                json.writeValueAsString(new SalesReceipts.SocialImport("form-1", 3, 1)));
    }

    @Test
    void salesRequests_parseLeniently_andIgnoreWhatTheyDoNotDeclare() throws Exception {
        SalesRequests.LeadRequest lead = json.readValue("{\"name\":\"Fleet\",\"companySize\":\"40\",\"state\":\"qualified\",\"score\":999}",
                SalesRequests.LeadRequest.class);
        assertEquals(40, lead.companySize());
        SalesRequests.OpportunityPatch patch = json.readValue("{\"state\":\"won\",\"quote\":{\"id\":\"q1\",\"href\":\"/x\"},"
                + "\"amount\":1200,\"probability\":\"75\",\"tenantId\":\"other\"}", SalesRequests.OpportunityPatch.class);
        assertEquals("q1", patch.quote().id());
        assertEquals(new BigDecimal("1200"), patch.amount());
        assertEquals(75, patch.probability());
        assertNull(patch.stage());
        SalesRequests.ActivityRequest act = json.readValue("{\"note\":\"Call\",\"dueDate\":\"2026-09-23T09:00:00Z\"}",
                SalesRequests.ActivityRequest.class);
        assertNull(act.type());
        assertEquals("2026-09-23T09:00:00Z", act.dueDate());
        SalesRequests.QuotaRequest quota = json.readValue("{\"ownerName\":\"Ada\",\"quotaPeriod\":\"2026-09\",\"amount\":\"5000\"}",
                SalesRequests.QuotaRequest.class);
        assertEquals(new BigDecimal("5000"), quota.amount());
        assertNull(quota.team());
    }
}
