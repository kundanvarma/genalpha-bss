package com.bss.quote.service;

import com.bss.quote.api.ApiConstants;
import com.bss.quote.entity.OpportunityActivity;
import com.bss.quote.entity.OpportunityItem;
import com.bss.quote.entity.LeadRoutingRule;
import com.bss.quote.entity.LeadScoringRule;
import com.bss.quote.entity.OpportunityStageHistory;
import com.bss.quote.entity.SalesLead;
import com.bss.quote.entity.SalesOpportunity;
import com.bss.quote.events.DomainEventPublisher;
import com.bss.quote.exception.BadRequestException;
import com.bss.quote.exception.NotFoundException;
import com.bss.quote.repository.OpportunityActivityRepository;
import com.bss.quote.repository.OpportunityItemRepository;
import com.bss.quote.repository.SalesLeadRepository;
import com.bss.quote.repository.SalesOpportunityRepository;
import com.bss.quote.security.TenantScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * TMF699 Sales Management: the funnel BEFORE anyone is a customer.
 * A salesLead arrives from the edge (the storefront's "Talk to sales"
 * form, a campaign, a CSR) and is acknowledged; sales either QUALIFIES it
 * — which mints a salesOpportunity to develop toward a quote — or marks
 * it unqualified. The opportunity closes won (ideally with the quote that
 * sealed it) or lost. Marketing creates the interest, this is where it
 * becomes revenue work.
 */
@Service
public class SalesService {

    private static final Logger log = LoggerFactory.getLogger(SalesService.class);

    private static final String DEFAULT_CURRENCY = "USD";

    private final SalesLeadRepository leads;
    private final SalesOpportunityRepository opportunities;
    private final OpportunityItemRepository items;
    private final OpportunityActivityRepository activities;
    private final com.bss.quote.repository.OpportunityStageHistoryRepository stageHistory;
    private final com.bss.quote.repository.LeadScoringRuleRepository scoringRules;
    private final com.bss.quote.repository.LeadRoutingRuleRepository routingRules;
    private final com.bss.quote.repository.SalesQuotaRepository quotaRepo;
    private final com.bss.quote.repository.PipelineSnapshotRepository snapshots;
    private final QuoteService quotes;
    private final DomainEventPublisher events;
    private final TenantScope tenantScope;
    private final com.bss.quote.security.TenantRegistry tenants;
    private final org.springframework.web.client.RestClient socialClient;
    private final int hotScore;
    private final int warmScore;

    public SalesService(SalesLeadRepository leads, SalesOpportunityRepository opportunities,
            OpportunityItemRepository items, OpportunityActivityRepository activities,
            com.bss.quote.repository.OpportunityStageHistoryRepository stageHistory,
            com.bss.quote.repository.LeadScoringRuleRepository scoringRules,
            com.bss.quote.repository.LeadRoutingRuleRepository routingRules,
            com.bss.quote.repository.SalesQuotaRepository quotaRepo,
            com.bss.quote.repository.PipelineSnapshotRepository snapshots,
            QuoteService quotes, DomainEventPublisher events, TenantScope tenantScope,
            com.bss.quote.security.TenantRegistry tenants,
            org.springframework.web.client.RestClient.Builder builder,
            @org.springframework.beans.factory.annotation.Value("${bss.sales.lead-hot-score:70}") int hotScore,
            @org.springframework.beans.factory.annotation.Value("${bss.sales.lead-warm-score:40}") int warmScore) {
        this.leads = leads;
        this.opportunities = opportunities;
        this.items = items;
        this.activities = activities;
        this.stageHistory = stageHistory;
        this.scoringRules = scoringRules;
        this.routingRules = routingRules;
        this.quotaRepo = quotaRepo;
        this.snapshots = snapshots;
        this.quotes = quotes;
        this.events = events;
        this.tenantScope = tenantScope;
        this.tenants = tenants;
        this.socialClient = builder.build();
        this.hotScore = hotScore;
        this.warmScore = warmScore;
    }

    /**
     * SOCIAL LEAD IMPORT: pull the tenant's lead-gen form entries (Meta
     * Lead Ads wire shape) into TMF699 salesLeads. Idempotent on the
     * platform's lead id — pull as often as you like.
     */
    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String, Object> importSocial() {
        String tenantId = tenantScope.currentTenantId();
        com.bss.quote.security.TenantRegistry.TenantEntry tenant = tenants.byId(tenantId);
        if (tenant == null || tenant.getSocialApiUrl() == null || tenant.getSocialApiUrl().isBlank()
                || tenant.getSocialLeadFormId() == null || tenant.getSocialLeadFormId().isBlank()) {
            throw new BadRequestException(
                    "no social lead form is configured for this tenant — the seam is per-tenant");
        }
        Map<String, Object> response = socialClient.get()
                .uri(tenant.getSocialApiUrl() + "/v1/" + tenant.getSocialLeadFormId() + "/leads")
                .header("Authorization", "Bearer " + tenant.getSocialAccessToken())
                .retrieve().body(Map.class);
        int imported = 0;
        int seen = 0;
        if (response != null && response.get("data") instanceof List<?> entries) {
            seen = entries.size();
            for (Object o : entries) {
                if (o instanceof Map<?, ?> entry && importOne((Map<String, Object>) entry, tenantId)) {
                    imported++;
                }
            }
        }
        log.info("social lead import for '{}': {} entries at the platform, {} new", tenantId, seen, imported);
        return Map.of("form", tenant.getSocialLeadFormId(), "entries", seen, "imported", imported);
    }

    private boolean importOne(Map<String, Object> entry, String tenantId) {
        String socialRef = entry.get("id") == null ? null : String.valueOf(entry.get("id"));
        if (socialRef == null || leads.existsByTenantIdAndSocialRef(tenantId, socialRef)) {
            return false;
        }
        Map<String, String> fields = new LinkedHashMap<>();
        if (entry.get("field_data") instanceof List<?> data) {
            for (Object f : data) {
                if (f instanceof Map<?, ?> field && field.get("name") != null
                        && field.get("values") instanceof List<?> values && !values.isEmpty()) {
                    fields.put(String.valueOf(field.get("name")), String.valueOf(values.get(0)));
                }
            }
        }
        SalesLead lead = new SalesLead();
        String id = UUID.randomUUID().toString();
        lead.setId(id);
        lead.setTenantId(tenantId);
        lead.setHref(ApiConstants.SALES_BASE + "/salesLead/" + id);
        lead.setName(fields.getOrDefault("need",
                "Social lead — " + fields.getOrDefault("full_name", socialRef)));
        lead.setContactName(fields.get("full_name"));
        lead.setContactEmail(fields.get("email"));
        lead.setCompany(fields.get("company"));
        lead.setSource("social");
        lead.setSocialRef(socialRef);
        lead.setState(SalesLead.ACKNOWLEDGED);
        if (fields.get("company_size") != null) {
            try { lead.setCompanySize(Integer.parseInt(fields.get("company_size").trim())); }
            catch (NumberFormatException ignore) { /* not a number */ }
        }
        scoreAndRoute(lead);
        lead.setCreatedAt(OffsetDateTime.now());
        lead.setLastUpdate(OffsetDateTime.now());
        Map<String, Object> created = leadToMap(leads.save(lead));
        events.publish("SalesLeadCreateEvent", "salesLead", created);
        return true;
    }

    /** Anyone may knock: the capture endpoint is open (the tenant comes
     * from the verified token or the gateway's hostname mapping). */
    @Transactional
    public Map<String, Object> createLead(Map<String, Object> dto) {
        if (dto.get("name") == null || String.valueOf(dto.get("name")).isBlank()) {
            throw new BadRequestException("name is required — what is the lead about?");
        }
        SalesLead lead = new SalesLead();
        String id = UUID.randomUUID().toString();
        lead.setId(id);
        lead.setTenantId(tenantScope.currentTenantId());
        lead.setHref(ApiConstants.SALES_BASE + "/salesLead/" + id);
        lead.setName(truncate(String.valueOf(dto.get("name")), 255));
        lead.setDescription(dto.get("description") == null ? null
                : truncate(String.valueOf(dto.get("description")), 2000));
        lead.setContactName(str(dto.get("contactName")));
        lead.setContactEmail(str(dto.get("contactEmail")));
        lead.setCompany(str(dto.get("company")));
        lead.setSource(dto.get("source") == null ? "storefront" : str(dto.get("source")));
        lead.setState(SalesLead.ACKNOWLEDGED);
        if (dto.get("companySize") != null) lead.setCompanySize(asInt(dto.get("companySize")));
        scoreAndRoute(lead);
        lead.setCreatedAt(OffsetDateTime.now());
        lead.setLastUpdate(OffsetDateTime.now());
        Map<String, Object> created = leadToMap(leads.save(lead));
        events.publish("SalesLeadCreateEvent", "salesLead", created);
        log.info("sales lead '{}' acknowledged (source: {}, score: {}, grade: {}, owner: {})",
                lead.getName(), lead.getSource(), lead.getScore(), lead.getGrade(), lead.getOwnerName());
        return created;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> findLeads() {
        return leads.findByTenantIdOrderByCreatedAtDesc(tenantScope.currentTenantId())
                .stream().map(this::leadToMap).toList();
    }

    // ---------------- O2: lead scoring + routing ----------------

    /** Score a lead from the tenant's scoring rules, grade it, and route it to
     *  an owner. Runs on capture; the opportunity later inherits the owner. */
    private void scoreAndRoute(SalesLead lead) {
        int score = 0;
        for (LeadScoringRule r : scoringRules.findByTenantIdOrderByCreatedAt(lead.getTenantId())) {
            boolean hit = switch (r.getField()) {
                case LeadScoringRule.SOURCE -> r.getValue() != null && r.getValue().equalsIgnoreCase(lead.getSource());
                case LeadScoringRule.COMPANY_PRESENT -> lead.getCompany() != null && !lead.getCompany().isBlank();
                case LeadScoringRule.COMPANY_SIZE_MIN -> lead.getCompanySize() != null
                        && r.getValue() != null && lead.getCompanySize() >= parseIntSafe(r.getValue());
                case LeadScoringRule.KEYWORD -> r.getValue() != null && containsCi(lead.getName(), r.getValue())
                        || (r.getValue() != null && containsCi(lead.getDescription(), r.getValue()));
                default -> false;
            };
            if (hit) score += r.getPoints();
        }
        lead.setScore(score);
        lead.setGrade(score >= hotScore ? "hot" : score >= warmScore ? "warm" : "cold");
        // Route to the highest band the score clears.
        String assignee = null;
        for (LeadRoutingRule rr : routingRules.findByTenantIdOrderByMinScoreDesc(lead.getTenantId())) {
            if (score >= rr.getMinScore()) { assignee = rr.getAssignee(); break; }
        }
        lead.setOwnerName(assignee);
    }

    private boolean containsCi(String haystack, String needle) {
        return haystack != null && haystack.toLowerCase().contains(needle.toLowerCase());
    }

    private int parseIntSafe(String s) {
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return Integer.MAX_VALUE; }
    }

    /** Recompute a lead's score/grade/owner (e.g. after the rules changed). */
    @Transactional
    public Map<String, Object> rescoreLead(String id) {
        SalesLead lead = requireLead(id);
        scoreAndRoute(lead);
        lead.setLastUpdate(OffsetDateTime.now());
        return leadToMap(leads.save(lead));
    }

    @Transactional
    public Map<String, Object> createScoringRule(Map<String, Object> dto) {
        String field = str(dto.get("field"));
        if (!List.of(LeadScoringRule.SOURCE, LeadScoringRule.COMPANY_PRESENT,
                LeadScoringRule.COMPANY_SIZE_MIN, LeadScoringRule.KEYWORD).contains(field)) {
            throw new BadRequestException("field must be source/companyPresent/companySizeMin/keyword");
        }
        LeadScoringRule r = new LeadScoringRule();
        r.setId(UUID.randomUUID().toString());
        r.setTenantId(tenantScope.currentTenantId());
        r.setField(field);
        r.setValue(str(dto.get("value")));
        r.setPoints(dto.get("points") == null ? 0 : asInt(dto.get("points")));
        r.setCreatedAt(OffsetDateTime.now());
        scoringRules.save(r);
        return scoringRuleToMap(r);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listScoringRules() {
        return scoringRules.findByTenantIdOrderByCreatedAt(tenantScope.currentTenantId())
                .stream().map(this::scoringRuleToMap).toList();
    }

    @Transactional
    public Map<String, Object> createRoutingRule(Map<String, Object> dto) {
        if (dto.get("assignee") == null) throw new BadRequestException("assignee is required");
        LeadRoutingRule r = new LeadRoutingRule();
        r.setId(UUID.randomUUID().toString());
        r.setTenantId(tenantScope.currentTenantId());
        r.setMinScore(dto.get("minScore") == null ? 0 : asInt(dto.get("minScore")));
        r.setAssignee(str(dto.get("assignee")));
        r.setCreatedAt(OffsetDateTime.now());
        routingRules.save(r);
        return routingRuleToMap(r);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listRoutingRules() {
        return routingRules.findByTenantIdOrderByMinScoreDesc(tenantScope.currentTenantId())
                .stream().map(this::routingRuleToMap).toList();
    }

    private Map<String, Object> scoringRuleToMap(LeadScoringRule r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("field", r.getField());
        if (r.getValue() != null) m.put("value", r.getValue());
        m.put("points", r.getPoints());
        return m;
    }

    private Map<String, Object> routingRuleToMap(LeadRoutingRule r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("minScore", r.getMinScore());
        m.put("assignee", r.getAssignee());
        return m;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> findLead(String id) {
        return leadToMap(requireLead(id));
    }

    /**
     * The lead's one decision: QUALIFIED (mints the opportunity — the
     * SPANCO step from suspect to prospect-with-a-deal) or UNQUALIFIED.
     * Either way the decision is final; leads are not re-litigated.
     */
    @Transactional
    public Map<String, Object> patchLead(String id, Map<String, Object> patch) {
        SalesLead lead = requireLead(id);
        String state = str(patch.get("state"));
        if (!SalesLead.QUALIFIED.equals(state) && !SalesLead.UNQUALIFIED.equals(state)) {
            throw new BadRequestException("state must be 'qualified' or 'unqualified'");
        }
        if (!SalesLead.ACKNOWLEDGED.equals(lead.getState())) {
            throw new BadRequestException("this lead was already " + lead.getState()
                    + " — the decision is final");
        }
        lead.setState(state);
        lead.setLastUpdate(OffsetDateTime.now());
        if (SalesLead.QUALIFIED.equals(state)) {
            SalesOpportunity opp = new SalesOpportunity();
            String oppId = UUID.randomUUID().toString();
            opp.setId(oppId);
            opp.setTenantId(lead.getTenantId());
            opp.setHref(ApiConstants.SALES_BASE + "/salesOpportunity/" + oppId);
            opp.setName(lead.getName());
            opp.setDescription(lead.getDescription());
            opp.setLeadId(lead.getId());
            // The opportunity inherits the owner the lead routed to.
            opp.setOwnerId(lead.getOwnerId());
            opp.setOwnerName(lead.getOwnerName());
            opp.setState(SalesOpportunity.DEVELOPED);
            // A qualified lead opens at the first pipeline stage; probability
            // rides with the stage until sales edits it.
            opp.setStage(SalesOpportunity.QUALIFICATION);
            opp.setProbability(SalesOpportunity.defaultProbability(SalesOpportunity.QUALIFICATION));
            opp.setForecastCategory(SalesOpportunity.defaultForecastCategory(SalesOpportunity.QUALIFICATION));
            opp.setStageChangedAt(OffsetDateTime.now());
            opp.setCurrency(DEFAULT_CURRENCY);
            // A deal can be with an account we already know (B2B expansion) —
            // then its activities mirror onto that party's 360.
            opp.setPartyId(str64(patch.get("partyId")));
            opp.setCreatedAt(OffsetDateTime.now());
            opp.setLastUpdate(OffsetDateTime.now());
            opportunities.save(opp);
            recordStage(opp, SalesOpportunity.QUALIFICATION);
            lead.setOpportunityId(oppId);
            events.publish("SalesOpportunityCreateEvent", "salesOpportunity", oppToMap(opp));
            logActivityInternal(opp, OpportunityActivity.LIFECYCLE,
                    "Qualified from lead — opportunity opened at Qualification");
            log.info("lead '{}' qualified into opportunity {}", lead.getName(), oppId);
        }
        return leadToMap(leads.save(lead));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> findOpportunities() {
        return opportunities.findByTenantIdOrderByCreatedAtDesc(tenantScope.currentTenantId())
                .stream().map(this::oppToMap).toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> findOpportunity(String id) {
        return oppToMap(requireOpportunity(id));
    }

    /**
     * Work the deal: move it along the pipeline, set its value/close date/owner,
     * or close it won (ideally with the quote that sealed it) or lost. A closed
     * deal stays closed. Only the fields present in the patch change.
     */
    @Transactional
    public Map<String, Object> patchOpportunity(String id, Map<String, Object> patch) {
        SalesOpportunity opp = requireOpportunity(id);
        if (!SalesOpportunity.DEVELOPED.equals(opp.getState())) {
            throw new BadRequestException("this opportunity is already " + opp.getState()
                    + " — closed deals stay closed");
        }
        List<String> beats = new ArrayList<>();

        // --- close: state won/lost, via explicit state or a closed stage ---
        String state = str(patch.get("state"));
        String stage = str(patch.get("stage"));
        boolean closeWon = SalesOpportunity.WON.equals(state) || SalesOpportunity.CLOSED_WON.equals(stage);
        boolean closeLost = SalesOpportunity.LOST.equals(state) || SalesOpportunity.CLOSED_LOST.equals(stage);
        if (closeWon || closeLost) {
            opp.setState(closeWon ? SalesOpportunity.WON : SalesOpportunity.LOST);
            opp.setStage(closeWon ? SalesOpportunity.CLOSED_WON : SalesOpportunity.CLOSED_LOST);
            opp.setProbability(closeWon ? 100 : 0);
            opp.setForecastCategory(closeWon ? SalesOpportunity.CAT_CLOSED : SalesOpportunity.CAT_OMITTED);
            opp.setStageChangedAt(OffsetDateTime.now());
            recordStage(opp, opp.getStage());
            if (patch.get("closeReason") != null) opp.setCloseReason(str(patch.get("closeReason")));
            if (patch.get("quote") instanceof Map<?, ?> quote && quote.get("id") != null) {
                opp.setQuoteRef(String.valueOf(quote.get("id")));
            }
            opp.setLastUpdate(OffsetDateTime.now());
            Map<String, Object> closed = oppToMap(opportunities.save(opp));
            String verb = closeWon ? "Won" : "Lost";
            logActivityInternal(opp, OpportunityActivity.LIFECYCLE, verb + " — "
                    + (opp.getCloseReason() == null ? "no reason given" : opp.getCloseReason()));
            events.publish("SalesOpportunityStateChangeEvent", "salesOpportunity", closed);
            log.info("opportunity {} closed {}", id, opp.getState());
            return closed;
        }

        // --- develop: field edits, no close ---
        if (stage != null) {
            if (!SalesOpportunity.isStage(stage) || SalesOpportunity.CLOSED_WON.equals(stage)
                    || SalesOpportunity.CLOSED_LOST.equals(stage)) {
                throw new BadRequestException("stage must be an open pipeline stage");
            }
            if (!stage.equals(opp.getStage())) {
                beats.add("Moved to " + stage);
                opp.setStage(stage);
                opp.setStageChangedAt(OffsetDateTime.now());
                recordStage(opp, stage);
                // Probability and forecast category ride with the stage unless
                // the deal overrides them.
                opp.setProbability(SalesOpportunity.defaultProbability(stage));
                opp.setForecastCategory(SalesOpportunity.defaultForecastCategory(stage));
            }
        }
        if (patch.get("forecastCategory") != null) {
            String c = str(patch.get("forecastCategory"));
            if (!SalesOpportunity.isForecastCategory(c)) {
                throw new BadRequestException("forecastCategory must be pipeline/bestCase/commit/closed/omitted");
            }
            opp.setForecastCategory(c);
        }
        if (patch.get("probability") != null) opp.setProbability(asInt(patch.get("probability")));
        if (patch.get("amount") != null) opp.setAmount(asDecimal(patch.get("amount")));
        if (patch.get("currency") != null) opp.setCurrency(str(patch.get("currency")));
        if (patch.get("expectedCloseDate") != null) {
            opp.setExpectedCloseDate(LocalDate.parse(str(patch.get("expectedCloseDate"))));
        }
        if (patch.get("ownerId") != null) opp.setOwnerId(str64(patch.get("ownerId")));
        if (patch.get("ownerName") != null) opp.setOwnerName(str(patch.get("ownerName")));
        if (patch.get("partyId") != null) opp.setPartyId(str64(patch.get("partyId")));
        if (patch.get("description") != null) {
            opp.setDescription(truncate(String.valueOf(patch.get("description")), 2000));
        }
        opp.setLastUpdate(OffsetDateTime.now());
        Map<String, Object> updated = oppToMap(opportunities.save(opp));
        for (String beat : beats) {
            logActivityInternal(opp, OpportunityActivity.LIFECYCLE, beat);
        }
        events.publish("SalesOpportunityAttributeValueChangeEvent", "salesOpportunity", updated);
        return updated;
    }

    // ---------------- line items: the deal's composition ----------------

    /** Add a product offering (TMF620 catalog) as a line on the deal; the
     *  opportunity's amount becomes the sum of its lines. */
    @Transactional
    public Map<String, Object> addItem(String opportunityId, Map<String, Object> dto) {
        SalesOpportunity opp = requireOpportunity(opportunityId);
        if (dto.get("offeringName") == null || String.valueOf(dto.get("offeringName")).isBlank()) {
            throw new BadRequestException("offeringName is required — what product is on the deal?");
        }
        OpportunityItem item = new OpportunityItem();
        String itemId = UUID.randomUUID().toString();
        item.setId(itemId);
        item.setTenantId(opp.getTenantId());
        item.setOpportunityId(opp.getId());
        item.setOfferingId(str64(dto.get("offeringId")));
        item.setOfferingName(str(dto.get("offeringName")));
        item.setQuantity(dto.get("quantity") == null ? 1 : Math.max(1, asInt(dto.get("quantity"))));
        item.setUnitPrice(dto.get("unitPrice") == null ? BigDecimal.ZERO : asDecimal(dto.get("unitPrice")));
        item.setCurrency(opp.getCurrency() == null ? DEFAULT_CURRENCY : opp.getCurrency());
        item.setRecurring(!Boolean.FALSE.equals(dto.get("recurring")));
        item.setCreatedAt(OffsetDateTime.now());
        items.save(item);
        recomputeAmount(opp);
        logActivityInternal(opp, OpportunityActivity.NOTE,
                "Added " + item.getQuantity() + "× " + item.getOfferingName() + " to the deal");
        return oppToMap(requireOpportunity(opportunityId));
    }

    @Transactional
    public Map<String, Object> removeItem(String opportunityId, String itemId) {
        SalesOpportunity opp = requireOpportunity(opportunityId);
        OpportunityItem item = items.findByIdAndTenantId(itemId, opp.getTenantId())
                .orElseThrow(() -> NotFoundException.forResource("OpportunityItem", itemId));
        items.delete(item);
        recomputeAmount(opp);
        return oppToMap(requireOpportunity(opportunityId));
    }

    /** Amount tracks the sum of the lines whenever any exist. */
    private void recomputeAmount(SalesOpportunity opp) {
        List<OpportunityItem> lines = items.findByTenantIdAndOpportunityIdOrderByCreatedAt(
                opp.getTenantId(), opp.getId());
        if (!lines.isEmpty()) {
            BigDecimal sum = BigDecimal.ZERO;
            for (OpportunityItem l : lines) {
                sum = sum.add(l.getUnitPrice().multiply(BigDecimal.valueOf(l.getQuantity())));
            }
            opp.setAmount(sum);
            opp.setLastUpdate(OffsetDateTime.now());
            opportunities.save(opp);
        }
    }

    // ---------------- activities: the sales workspace ----------------

    /** Log a call/email/note on the deal, OR set a next-step TASK: pass a
     *  dueDate (+ optional assignee) and it becomes an OPEN task that shows on
     *  the "my open tasks" queue until marked done. Mirrors onto the party's
     *  TMF683 360 timeline when the deal is with a known account. */
    @Transactional
    public Map<String, Object> logActivity(String opportunityId, Map<String, Object> dto) {
        SalesOpportunity opp = requireOpportunity(opportunityId);
        if (dto.get("note") == null || String.valueOf(dto.get("note")).isBlank()) {
            throw new BadRequestException("note is required — what happened, or what's next?");
        }
        String type = str(dto.get("type"));
        if (type == null) type = dto.get("dueDate") != null ? OpportunityActivity.NEXT_STEP : OpportunityActivity.NOTE;
        OffsetDateTime due = dto.get("dueDate") == null ? null : OffsetDateTime.parse(str(dto.get("dueDate")));
        String status = due != null ? OpportunityActivity.OPEN : OpportunityActivity.DONE;
        logActivityInternal(opp, type, truncate(String.valueOf(dto.get("note")), 2000),
                due, status, str(dto.get("assignee")));
        return Map.of("opportunityId", opportunityId, "activities",
                activities.findByTenantIdAndOpportunityIdOrderByOccurredAtDesc(
                        opp.getTenantId(), opportunityId).stream().map(this::activityToMap).toList());
    }

    /** Mark an open task done. */
    @Transactional
    public Map<String, Object> completeTask(String opportunityId, String activityId) {
        SalesOpportunity opp = requireOpportunity(opportunityId);
        OpportunityActivity a = activities.findByTenantIdAndOpportunityIdOrderByOccurredAtDesc(
                        opp.getTenantId(), opportunityId).stream()
                .filter(x -> x.getId().equals(activityId)).findFirst()
                .orElseThrow(() -> NotFoundException.forResource("OpportunityActivity", activityId));
        a.setStatus(OpportunityActivity.DONE);
        activities.save(a);
        return activityToMap(a);
    }

    /** The open-tasks queue — every open next-step across the pipeline, soonest
     *  due first; optionally just one assignee's. Overdue ones are flagged. */
    @Transactional(readOnly = true)
    public Map<String, Object> openTasks(String assignee) {
        String tenantId = tenantScope.currentTenantId();
        List<OpportunityActivity> open = assignee == null
                ? activities.findByTenantIdAndStatusOrderByDueDateAsc(tenantId, OpportunityActivity.OPEN)
                : activities.findByTenantIdAndStatusAndAssigneeOrderByDueDateAsc(
                        tenantId, OpportunityActivity.OPEN, assignee);
        OffsetDateTime now = OffsetDateTime.now();
        List<Map<String, Object>> tasks = new ArrayList<>();
        for (OpportunityActivity a : open) {
            Map<String, Object> m = activityToMap(a);
            m.put("opportunityId", a.getOpportunityId());
            m.put("overdue", a.getDueDate() != null && a.getDueDate().isBefore(now));
            if (a.getAssignee() != null) m.put("assignee", a.getAssignee());
            tasks.add(m);
        }
        return Map.of("openCount", tasks.size(), "tasks", tasks);
    }

    private void logActivityInternal(SalesOpportunity opp, String type, String note) {
        logActivityInternal(opp, type, note, null, OpportunityActivity.DONE, null);
    }

    private void logActivityInternal(SalesOpportunity opp, String type, String note,
            OffsetDateTime dueDate, String status, String assignee) {
        OpportunityActivity a = new OpportunityActivity();
        String actId = UUID.randomUUID().toString();
        a.setId(actId);
        a.setTenantId(opp.getTenantId());
        a.setOpportunityId(opp.getId());
        a.setPartyId(opp.getPartyId());
        a.setActivityType(type);
        a.setNote(note);
        a.setOccurredAt(OffsetDateTime.now());
        a.setDueDate(dueDate);
        a.setStatus(status);
        a.setAssignee(assignee);
        activities.save(a);
        // The event carries the party id (when known) so party-interaction can
        // mint a TMF683 touchpoint — sales on the customer 360.
        Map<String, Object> evt = new LinkedHashMap<>();
        evt.put("id", actId);
        evt.put("opportunityId", opp.getId());
        evt.put("opportunityName", opp.getName());
        if (opp.getPartyId() != null) evt.put("partyId", opp.getPartyId());
        evt.put("type", type);
        evt.put("note", note);
        events.publish("SalesActivityCreateEvent", "salesActivity", evt);
    }

    // ---------------- CPQ C1: the opportunity → quote hand-off ----------------

    /** Turn the deal's negotiated line items into a TMF648 quote in one step,
     *  and link it back to the opportunity. MRR from recurring lines, one-off
     *  from the rest. */
    @Transactional
    public Map<String, Object> buildQuote(String opportunityId) {
        SalesOpportunity opp = requireOpportunity(opportunityId);
        List<OpportunityItem> lines = items.findByTenantIdAndOpportunityIdOrderByCreatedAt(
                opp.getTenantId(), opp.getId());
        if (lines.isEmpty()) {
            throw new BadRequestException("add line items to the opportunity before quoting");
        }
        List<Map<String, Object>> lineItems = new ArrayList<>();
        for (OpportunityItem l : lines) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("offeringId", l.getOfferingId());
            m.put("offeringName", l.getOfferingName());
            m.put("quantity", l.getQuantity());
            m.put("unitPrice", l.getUnitPrice());
            m.put("recurring", l.isRecurring());
            lineItems.add(m);
        }
        Map<String, Object> quote = quotes.createFromLineItems(
                "Quote for " + opp.getName(), opp.getPartyId(), opp.getCurrency(), lineItems);
        opp.setQuoteRef(String.valueOf(quote.get("id")));
        opp.setLastUpdate(OffsetDateTime.now());
        opportunities.save(opp);
        logActivityInternal(opp, OpportunityActivity.LIFECYCLE,
                "Quote generated from the deal's line items");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("opportunity", oppToMap(requireOpportunity(opportunityId)));
        out.put("quote", quote);
        return out;
    }

    /** Guided selling → deal: run the answers through the recommendation rules
     *  and add the recommended offerings to the opportunity as line items. */
    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String, Object> applyGuided(String opportunityId, Map<String, Object> answers) {
        SalesOpportunity opp = requireOpportunity(opportunityId);
        Map<String, Object> reco = quotes.recommend(answers);
        List<Map<String, Object>> recs = reco.get("recommendations") instanceof List<?> l
                ? (List<Map<String, Object>>) l : List.of();
        for (Map<String, Object> r : recs) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("offeringName", r.get("offeringName"));
            item.put("quantity", r.get("quantity"));
            item.put("unitPrice", 0); // guided sizing picks products; the rep prices them
            addItem(opportunityId, item);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("applied", recs.size());
        out.put("recommendations", recs);
        out.put("opportunity", oppToMap(requireOpportunity(opportunityId)));
        return out;
    }

    // ---------------- O2: quota + attainment ----------------

    @Transactional
    public Map<String, Object> createQuota(Map<String, Object> dto) {
        if (dto.get("ownerName") == null || dto.get("quotaPeriod") == null || dto.get("amount") == null) {
            throw new BadRequestException("ownerName, quotaPeriod (YYYY-MM) and amount are required");
        }
        com.bss.quote.entity.SalesQuota q = new com.bss.quote.entity.SalesQuota();
        q.setId(UUID.randomUUID().toString());
        q.setTenantId(tenantScope.currentTenantId());
        q.setOwnerName(str(dto.get("ownerName")));
        q.setQuotaPeriod(str(dto.get("quotaPeriod")));
        q.setAmount(asDecimal(dto.get("amount")));
        q.setTeam(str(dto.get("team")));
        q.setCreatedAt(OffsetDateTime.now());
        quotaRepo.save(q);
        return quotaToMap(q);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listQuotas() {
        return quotaRepo.findByTenantIdOrderByCreatedAt(tenantScope.currentTenantId())
                .stream().map(this::quotaToMap).toList();
    }

    /** Quota attainment for a period: per owner, the quota vs won-in-period vs
     *  the probability-weighted open forecast (coverage) — the VP's Monday view. */
    @Transactional(readOnly = true)
    public Map<String, Object> quotaAttainment(String period) {
        String tenantId = tenantScope.currentTenantId();
        List<com.bss.quote.entity.SalesQuota> quotas = quotaRepo
                .findByTenantIdAndQuotaPeriodOrderByOwnerName(tenantId, period);
        List<SalesOpportunity> opps = opportunities.findByTenantIdOrderByCreatedAtDesc(tenantId);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (com.bss.quote.entity.SalesQuota q : quotas) {
            BigDecimal won = BigDecimal.ZERO;
            BigDecimal weightedOpen = BigDecimal.ZERO;
            for (SalesOpportunity o : opps) {
                if (o.getOwnerName() == null || !o.getOwnerName().equals(q.getOwnerName())) continue;
                if (SalesOpportunity.WON.equals(o.getState())) {
                    // closed-won in this period (by the stage-change/close time)
                    if (o.getStageChangedAt() != null && period.equals(yearMonth(o.getStageChangedAt()))) {
                        won = won.add(o.getAmount() == null ? BigDecimal.ZERO : o.getAmount());
                    }
                } else if (SalesOpportunity.DEVELOPED.equals(o.getState())) {
                    BigDecimal a = o.getAmount() == null ? BigDecimal.ZERO : o.getAmount();
                    int p = o.getProbability() == null
                            ? SalesOpportunity.defaultProbability(o.getStage()) : o.getProbability();
                    weightedOpen = weightedOpen.add(a.multiply(BigDecimal.valueOf(p)).divide(BigDecimal.valueOf(100)));
                }
            }
            BigDecimal quota = q.getAmount();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("owner", q.getOwnerName());
            if (q.getTeam() != null) row.put("team", q.getTeam());
            row.put("quota", quota);
            row.put("won", won);
            row.put("weightedOpen", weightedOpen);
            row.put("attainmentPct", pct(won, quota));
            row.put("coveragePct", pct(won.add(weightedOpen), quota));
            rows.add(row);
        }
        // Team roll-up: owners aggregate to their team (quota, won, weighted).
        Map<String, BigDecimal[]> teamAgg = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            String team = r.get("team") == null ? "(no team)" : String.valueOf(r.get("team"));
            BigDecimal[] a = teamAgg.computeIfAbsent(team,
                    k -> new BigDecimal[] { BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO });
            a[0] = a[0].add((BigDecimal) r.get("quota"));
            a[1] = a[1].add((BigDecimal) r.get("won"));
            a[2] = a[2].add((BigDecimal) r.get("weightedOpen"));
        }
        List<Map<String, Object>> byTeam = new ArrayList<>();
        for (Map.Entry<String, BigDecimal[]> e : teamAgg.entrySet()) {
            BigDecimal[] a = e.getValue();
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("team", e.getKey());
            t.put("quota", a[0]);
            t.put("won", a[1]);
            t.put("weightedOpen", a[2]);
            t.put("attainmentPct", pct(a[1], a[0]));
            t.put("coveragePct", pct(a[1].add(a[2]), a[0]));
            byTeam.add(t);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("period", period);
        out.put("owners", rows);
        out.put("byTeam", byTeam);
        return out;
    }

    // ---------------- O2: weekly pipeline snapshot (forecast-over-time) ----------------

    /** Capture the current open weighted forecast for the acting tenant. */
    @Transactional
    public Map<String, Object> captureSnapshot() {
        Map<String, Object> p = pipeline();
        com.bss.quote.entity.PipelineSnapshot s = new com.bss.quote.entity.PipelineSnapshot();
        s.setId(UUID.randomUUID().toString());
        s.setTenantId(tenantScope.currentTenantId());
        s.setCapturedAt(OffsetDateTime.now());
        s.setOpenCount(((Number) p.get("openCount")).intValue());
        s.setOpenAmount((BigDecimal) p.get("openAmount"));
        s.setWeightedForecast((BigDecimal) p.get("weightedForecast"));
        s.setCurrency(String.valueOf(p.get("currency")));
        snapshots.save(s);
        return snapshotToMap(s);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listSnapshots() {
        return snapshots.findTop52ByTenantIdOrderByCapturedAtDesc(tenantScope.currentTenantId())
                .stream().map(this::snapshotToMap).toList();
    }

    /** The weekly scheduled capture — one snapshot per tenant, so forecast
     *  over time and slippage are visible without anyone remembering to click. */
    @org.springframework.scheduling.annotation.Scheduled(
            cron = "${bss.sales.snapshot-cron:0 0 6 * * MON}")
    public void weeklySnapshot() {
        for (com.bss.quote.security.TenantRegistry.TenantEntry t : tenants.getRegistry()) {
            if (t.getId() == null) continue;
            try (com.bss.quote.security.TenantContext ignored
                    = com.bss.quote.security.TenantContext.actAs(t.getId())) {
                captureSnapshot();
            } catch (Exception e) {
                log.warn("weekly pipeline snapshot failed for tenant {}: {}", t.getId(), e.getMessage());
            }
        }
    }

    private Map<String, Object> snapshotToMap(com.bss.quote.entity.PipelineSnapshot s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("capturedAt", s.getCapturedAt());
        m.put("openCount", s.getOpenCount());
        m.put("openAmount", s.getOpenAmount());
        m.put("weightedForecast", s.getWeightedForecast());
        m.put("currency", s.getCurrency());
        return m;
    }

    private double pct(BigDecimal num, BigDecimal denom) {
        if (denom == null || denom.signum() == 0) return 0;
        return Math.round(num.multiply(BigDecimal.valueOf(1000)).divide(denom, java.math.RoundingMode.HALF_UP)
                .doubleValue()) / 10.0;
    }

    private String yearMonth(OffsetDateTime t) {
        return String.format("%04d-%02d", t.getYear(), t.getMonthValue());
    }

    private Map<String, Object> quotaToMap(com.bss.quote.entity.SalesQuota q) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", q.getId());
        m.put("ownerName", q.getOwnerName());
        m.put("quotaPeriod", q.getQuotaPeriod());
        m.put("amount", q.getAmount());
        if (q.getTeam() != null) m.put("team", q.getTeam());
        return m;
    }

    // ---------------- pipeline & forecast ----------------

    /** The board: open deals grouped by stage with count, value, and the
     *  probability-weighted forecast — the number a sales manager commits. */
    @Transactional(readOnly = true)
    public Map<String, Object> pipeline() {
        String tenantId = tenantScope.currentTenantId();
        List<SalesOpportunity> all = opportunities.findByTenantIdOrderByCreatedAtDesc(tenantId);
        String[] order = { SalesOpportunity.QUALIFICATION, SalesOpportunity.NEEDS_ANALYSIS,
                SalesOpportunity.PROPOSAL, SalesOpportunity.NEGOTIATION };
        List<Map<String, Object>> stages = new ArrayList<>();
        BigDecimal openAmount = BigDecimal.ZERO;
        BigDecimal weighted = BigDecimal.ZERO;
        int openCount = 0;
        for (String st : order) {
            int count = 0;
            BigDecimal amt = BigDecimal.ZERO;
            BigDecimal wtd = BigDecimal.ZERO;
            for (SalesOpportunity o : all) {
                if (!SalesOpportunity.DEVELOPED.equals(o.getState())) continue;
                if (!st.equals(o.getStage())) continue;
                count++;
                BigDecimal a = o.getAmount() == null ? BigDecimal.ZERO : o.getAmount();
                int p = o.getProbability() == null ? SalesOpportunity.defaultProbability(st) : o.getProbability();
                amt = amt.add(a);
                wtd = wtd.add(a.multiply(BigDecimal.valueOf(p)).divide(BigDecimal.valueOf(100)));
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("stage", st);
            row.put("count", count);
            row.put("amount", amt);
            row.put("weighted", wtd);
            stages.add(row);
            openCount += count;
            openAmount = openAmount.add(amt);
            weighted = weighted.add(wtd);
        }
        // Forecast categories: the number a manager commits, rolled up over the
        // open pipeline (Commit is the "will land" number; Best Case is upside).
        String[] cats = { SalesOpportunity.CAT_PIPELINE, SalesOpportunity.CAT_BEST_CASE,
                SalesOpportunity.CAT_COMMIT };
        List<Map<String, Object>> byCategory = new ArrayList<>();
        for (String cat : cats) {
            int count = 0;
            BigDecimal amt = BigDecimal.ZERO;
            for (SalesOpportunity o : all) {
                if (!SalesOpportunity.DEVELOPED.equals(o.getState())) continue;
                String c = o.getForecastCategory() == null
                        ? SalesOpportunity.defaultForecastCategory(o.getStage()) : o.getForecastCategory();
                if (!cat.equals(c)) continue;
                count++;
                amt = amt.add(o.getAmount() == null ? BigDecimal.ZERO : o.getAmount());
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("category", cat);
            row.put("count", count);
            row.put("amount", amt);
            byCategory.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("stages", stages);
        out.put("byCategory", byCategory);
        out.put("openCount", openCount);
        out.put("openAmount", openAmount);
        out.put("weightedForecast", weighted);
        out.put("currency", DEFAULT_CURRENCY);
        return out;
    }

    /** Record a stage entry for the funnel analytics. */
    private void recordStage(SalesOpportunity opp, String stage) {
        OpportunityStageHistory h = new OpportunityStageHistory();
        h.setId(UUID.randomUUID().toString());
        h.setTenantId(opp.getTenantId());
        h.setOpportunityId(opp.getId());
        h.setStage(stage);
        h.setEnteredAt(OffsetDateTime.now());
        stageHistory.save(h);
    }

    /**
     * Funnel analytics — the numbers a manager (or a copilot) reads: how deals
     * convert stage-to-stage, the win rate, the average sales cycle, and the
     * average time a deal spends in each stage. Computed off the stage history,
     * and returned WITH a plain-language summary so a copilot can narrate it.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> funnel() {
        String tenantId = tenantScope.currentTenantId();
        List<SalesOpportunity> opps = opportunities.findByTenantIdOrderByCreatedAtDesc(tenantId);
        Map<String, OffsetDateTime> created = new LinkedHashMap<>();
        for (SalesOpportunity o : opps) created.put(o.getId(), o.getCreatedAt());

        // Group the history by opportunity, in time order.
        Map<String, List<OpportunityStageHistory>> byOpp = new LinkedHashMap<>();
        for (OpportunityStageHistory h : stageHistory.findByTenantIdOrderByEnteredAt(tenantId)) {
            byOpp.computeIfAbsent(h.getOpportunityId(), k -> new ArrayList<>()).add(h);
        }

        String[] order = { SalesOpportunity.QUALIFICATION, SalesOpportunity.NEEDS_ANALYSIS,
                SalesOpportunity.PROPOSAL, SalesOpportunity.NEGOTIATION, SalesOpportunity.CLOSED_WON };
        Map<String, Integer> reached = new LinkedHashMap<>();
        for (String s : order) reached.put(s, 0);
        // Sum of durations spent in each stage, and a count, for the average.
        Map<String, long[]> stageDwell = new LinkedHashMap<>(); // stage -> [totalSeconds, count]

        long cycleSecondsTotal = 0;
        int cycleCount = 0;
        for (Map.Entry<String, List<OpportunityStageHistory>> e : byOpp.entrySet()) {
            List<OpportunityStageHistory> hist = e.getValue();
            java.util.Set<String> stagesSeen = new java.util.HashSet<>();
            for (int i = 0; i < hist.size(); i++) {
                String st = hist.get(i).getStage();
                if (reached.containsKey(st)) stagesSeen.add(st);
                // dwell = time until the next transition (only for exited stages)
                if (i + 1 < hist.size()) {
                    long secs = java.time.Duration.between(
                            hist.get(i).getEnteredAt(), hist.get(i + 1).getEnteredAt()).getSeconds();
                    long[] acc = stageDwell.computeIfAbsent(st, k -> new long[2]);
                    acc[0] += Math.max(0, secs);
                    acc[1] += 1;
                }
            }
            for (String s : stagesSeen) reached.merge(s, 1, Integer::sum);
            // cycle time: created → the closing (won or lost) entry
            OpportunityStageHistory close = hist.stream()
                    .filter(h -> SalesOpportunity.CLOSED_WON.equals(h.getStage())
                            || SalesOpportunity.CLOSED_LOST.equals(h.getStage()))
                    .reduce((a, b) -> b).orElse(null);
            if (close != null && created.get(e.getKey()) != null) {
                cycleSecondsTotal += Math.max(0, java.time.Duration.between(
                        created.get(e.getKey()), close.getEnteredAt()).getSeconds());
                cycleCount++;
            }
        }

        // Stage-to-stage conversion.
        List<Map<String, Object>> conversion = new ArrayList<>();
        for (int i = 0; i < order.length - 1; i++) {
            int from = reached.get(order[i]);
            int to = reached.get(order[i + 1]);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("from", order[i]);
            row.put("to", order[i + 1]);
            row.put("reachedFrom", from);
            row.put("reachedTo", to);
            row.put("conversionPct", from == 0 ? 0 : Math.round(to * 1000.0 / from) / 10.0);
            conversion.add(row);
        }
        // Average time-in-stage (days).
        List<Map<String, Object>> timeInStage = new ArrayList<>();
        for (String s : new String[] { SalesOpportunity.QUALIFICATION, SalesOpportunity.NEEDS_ANALYSIS,
                SalesOpportunity.PROPOSAL, SalesOpportunity.NEGOTIATION }) {
            long[] acc = stageDwell.getOrDefault(s, new long[2]);
            double days = acc[1] == 0 ? 0 : Math.round(acc[0] / (double) acc[1] / 86400.0 * 10) / 10.0;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("stage", s);
            row.put("avgDays", days);
            timeInStage.add(row);
        }

        int won = (int) opps.stream().filter(o -> SalesOpportunity.WON.equals(o.getState())).count();
        int lost = (int) opps.stream().filter(o -> SalesOpportunity.LOST.equals(o.getState())).count();
        double winRate = (won + lost) == 0 ? 0 : Math.round(won * 1000.0 / (won + lost)) / 10.0;
        double avgCycleDays = cycleCount == 0 ? 0 : Math.round(cycleSecondsTotal / (double) cycleCount / 86400.0 * 10) / 10.0;

        // A plain-language summary — the copilot narrates from this, and it
        // keeps us honest about thin samples.
        String weakest = conversion.stream()
                .filter(c -> (int) c.get("reachedFrom") > 0)
                .min((a, b) -> Double.compare((double) a.get("conversionPct"), (double) b.get("conversionPct")))
                .map(c -> c.get("from") + "→" + c.get("to")).orElse("n/a");
        String summary = "Win rate " + winRate + "% over " + (won + lost) + " closed deals; "
                + "average cycle " + avgCycleDays + " days. Weakest stage transition: " + weakest + "."
                + ((won + lost) < 5 ? " (Small sample — read as a hint, not a measurement.)" : "");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("stageConversion", conversion);
        out.put("timeInStage", timeInStage);
        out.put("winRatePct", winRate);
        out.put("wonCount", won);
        out.put("lostCount", lost);
        out.put("avgCycleDays", avgCycleDays);
        out.put("summary", summary);
        return out;
    }

    /** Which programme sourced the revenue: won deals grouped by their lead's
     *  source, with the closed amount — the honest B2B attribution number. */
    @Transactional(readOnly = true)
    public Map<String, Object> wonReport() {
        String tenantId = tenantScope.currentTenantId();
        Map<String, int[]> countBySource = new LinkedHashMap<>();
        Map<String, BigDecimal> amountBySource = new LinkedHashMap<>();
        BigDecimal total = BigDecimal.ZERO;
        int won = 0;
        for (SalesOpportunity o : opportunities.findByTenantIdOrderByCreatedAtDesc(tenantId)) {
            if (!SalesOpportunity.WON.equals(o.getState())) continue;
            won++;
            BigDecimal a = o.getAmount() == null ? BigDecimal.ZERO : o.getAmount();
            total = total.add(a);
            String source = "unknown";
            if (o.getLeadId() != null) {
                SalesLead lead = leads.findByIdAndTenantId(o.getLeadId(), tenantId).orElse(null);
                if (lead != null && lead.getSource() != null) source = lead.getSource();
            }
            countBySource.computeIfAbsent(source, k -> new int[1])[0]++;
            amountBySource.merge(source, a, BigDecimal::add);
        }
        List<Map<String, Object>> bySource = new ArrayList<>();
        for (String s : amountBySource.keySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("source", s);
            row.put("wonCount", countBySource.get(s)[0]);
            row.put("wonAmount", amountBySource.get(s));
            bySource.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("wonCount", won);
        out.put("wonAmount", total);
        out.put("bySource", bySource);
        out.put("currency", DEFAULT_CURRENCY);
        return out;
    }

    private SalesLead requireLead(String id) {
        return leads.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource("SalesLead", id));
    }

    private SalesOpportunity requireOpportunity(String id) {
        return opportunities.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource("SalesOpportunity", id));
    }

    private String str(Object v) {
        return v == null ? null : truncate(String.valueOf(v), 255);
    }

    private String str64(Object v) {
        return v == null ? null : truncate(String.valueOf(v), 64);
    }

    private int asInt(Object v) {
        try {
            return v instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            throw new BadRequestException("expected a number, got: " + v);
        }
    }

    private BigDecimal asDecimal(Object v) {
        try {
            return v instanceof Number n ? BigDecimal.valueOf(n.doubleValue())
                    : new BigDecimal(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            throw new BadRequestException("expected an amount, got: " + v);
        }
    }

    private String truncate(String v, int max) {
        return v.length() > max ? v.substring(0, max) : v;
    }

    private Map<String, Object> itemToMap(OpportunityItem i) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", i.getId());
        if (i.getOfferingId() != null) m.put("offeringId", i.getOfferingId());
        m.put("offeringName", i.getOfferingName());
        m.put("quantity", i.getQuantity());
        m.put("unitPrice", i.getUnitPrice());
        m.put("lineTotal", i.getUnitPrice().multiply(BigDecimal.valueOf(i.getQuantity())));
        if (i.getCurrency() != null) m.put("currency", i.getCurrency());
        return m;
    }

    private Map<String, Object> activityToMap(OpportunityActivity a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("type", a.getActivityType());
        m.put("note", a.getNote());
        m.put("occurredAt", a.getOccurredAt());
        m.put("status", a.getStatus());
        if (a.getDueDate() != null) m.put("dueDate", a.getDueDate());
        if (a.getAssignee() != null) m.put("assignee", a.getAssignee());
        return m;
    }

    private Map<String, Object> leadToMap(SalesLead lead) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", lead.getId());
        map.put("href", lead.getHref());
        map.put("name", lead.getName());
        if (lead.getDescription() != null) map.put("description", lead.getDescription());
        if (lead.getContactName() != null) map.put("contactName", lead.getContactName());
        if (lead.getContactEmail() != null) map.put("contactEmail", lead.getContactEmail());
        if (lead.getCompany() != null) map.put("company", lead.getCompany());
        map.put("source", lead.getSource());
        map.put("state", lead.getState());
        map.put("score", lead.getScore());
        if (lead.getGrade() != null) map.put("grade", lead.getGrade());
        if (lead.getOwnerName() != null) {
            Map<String, Object> owner = new LinkedHashMap<>();
            if (lead.getOwnerId() != null) owner.put("id", lead.getOwnerId());
            owner.put("name", lead.getOwnerName());
            map.put("owner", owner);
        }
        if (lead.getCompanySize() != null) map.put("companySize", lead.getCompanySize());
        if (lead.getOpportunityId() != null) {
            map.put("salesOpportunity", Map.of("id", lead.getOpportunityId(),
                    "href", ApiConstants.SALES_BASE + "/salesOpportunity/" + lead.getOpportunityId()));
        }
        map.put("creationDate", lead.getCreatedAt());
        map.put("lastUpdate", lead.getLastUpdate());
        map.put("@type", "SalesLead");
        return map;
    }

    private Map<String, Object> oppToMap(SalesOpportunity opp) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", opp.getId());
        map.put("href", opp.getHref());
        map.put("name", opp.getName());
        if (opp.getDescription() != null) map.put("description", opp.getDescription());
        if (opp.getLeadId() != null) {
            map.put("salesLead", Map.of("id", opp.getLeadId(),
                    "href", ApiConstants.SALES_BASE + "/salesLead/" + opp.getLeadId()));
        }
        map.put("state", opp.getState());
        if (opp.getStage() != null) map.put("stage", opp.getStage());
        if (opp.getForecastCategory() != null) map.put("forecastCategory", opp.getForecastCategory());
        if (opp.getProbability() != null) map.put("probability", opp.getProbability());
        if (opp.getStageChangedAt() != null) {
            map.put("stageChangedAt", opp.getStageChangedAt());
            map.put("daysInStage", java.time.Duration.between(
                    opp.getStageChangedAt(), OffsetDateTime.now()).toDays());
        }
        if (opp.getAmount() != null) map.put("amount", opp.getAmount());
        if (opp.getCurrency() != null) map.put("currency", opp.getCurrency());
        if (opp.getExpectedCloseDate() != null) map.put("expectedCloseDate", opp.getExpectedCloseDate().toString());
        if (opp.getOwnerId() != null || opp.getOwnerName() != null) {
            Map<String, Object> owner = new LinkedHashMap<>();
            if (opp.getOwnerId() != null) owner.put("id", opp.getOwnerId());
            if (opp.getOwnerName() != null) owner.put("name", opp.getOwnerName());
            map.put("owner", owner);
        }
        if (opp.getPartyId() != null) map.put("partyId", opp.getPartyId());
        if (opp.getCloseReason() != null) map.put("closeReason", opp.getCloseReason());
        if (opp.getQuoteRef() != null) {
            map.put("quote", Map.of("id", opp.getQuoteRef(),
                    "href", ApiConstants.BASE_PATH + "/quote/" + opp.getQuoteRef()));
        }
        // the deal's composition and its workspace, for the console detail
        List<Map<String, Object>> lines = items.findByTenantIdAndOpportunityIdOrderByCreatedAt(
                opp.getTenantId(), opp.getId()).stream().map(this::itemToMap).toList();
        if (!lines.isEmpty()) map.put("items", lines);
        List<Map<String, Object>> log = activities.findByTenantIdAndOpportunityIdOrderByOccurredAtDesc(
                opp.getTenantId(), opp.getId()).stream().map(this::activityToMap).toList();
        if (!log.isEmpty()) map.put("activities", log);
        map.put("creationDate", opp.getCreatedAt());
        map.put("lastUpdate", opp.getLastUpdate());
        map.put("@type", "SalesOpportunity");
        return map;
    }
}
