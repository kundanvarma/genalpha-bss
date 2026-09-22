package com.bss.quote.service;

import com.bss.quote.api.ApiConstants;
import com.bss.quote.dto.ActivityLog;
import com.bss.quote.dto.ActivityView;
import com.bss.quote.dto.FunnelReport;
import com.bss.quote.dto.FunnelReport.StageConversion;
import com.bss.quote.dto.FunnelReport.TimeInStage;
import com.bss.quote.dto.GuidedSelling.Recommendation;
import com.bss.quote.dto.LeadRules.RoutingRuleView;
import com.bss.quote.dto.LeadRules.ScoringRuleView;
import com.bss.quote.dto.LeadSignal;
import com.bss.quote.dto.LeadView;
import com.bss.quote.dto.LineItem;
import com.bss.quote.dto.OpenTasks;
import com.bss.quote.dto.OpenTasks.TaskView;
import com.bss.quote.dto.OpportunityItemView;
import com.bss.quote.dto.OpportunityView;
import com.bss.quote.dto.PipelineBoard;
import com.bss.quote.dto.PipelineBoard.CategoryColumn;
import com.bss.quote.dto.PipelineBoard.StageColumn;
import com.bss.quote.dto.QuotaAttainment;
import com.bss.quote.dto.QuotaAttainment.OwnerRow;
import com.bss.quote.dto.QuotaAttainment.TeamRow;
import com.bss.quote.dto.QuotaView;
import com.bss.quote.dto.QuoteView;
import com.bss.quote.dto.SalesReceipts.GuidedApplied;
import com.bss.quote.dto.SalesReceipts.QuoteHandoff;
import com.bss.quote.dto.SalesReceipts.SocialImport;
import com.bss.quote.dto.SalesRequests.ActivityRequest;
import com.bss.quote.dto.SalesRequests.LeadPatch;
import com.bss.quote.dto.SalesRequests.LeadRequest;
import com.bss.quote.dto.SalesRequests.OpportunityPatch;
import com.bss.quote.dto.SalesRequests.QuotaRequest;
import com.bss.quote.dto.SalesRequests.RoutingRuleRequest;
import com.bss.quote.dto.SalesRequests.ScoringRuleRequest;
import com.bss.quote.dto.SnapshotView;
import com.bss.quote.dto.WonReport;
import com.bss.quote.dto.WonReport.SourceRow;
import com.bss.quote.entity.LeadRoutingRule;
import com.bss.quote.entity.LeadScoringRule;
import com.bss.quote.entity.OpportunityActivity;
import com.bss.quote.entity.OpportunityItem;
import com.bss.quote.entity.OpportunityStageHistory;
import com.bss.quote.entity.PipelineSnapshot;
import com.bss.quote.entity.SalesLead;
import com.bss.quote.entity.SalesOpportunity;
import com.bss.quote.entity.SalesQuota;
import com.bss.quote.events.DomainEventPublisher;
import com.bss.quote.exception.BadRequestException;
import com.bss.quote.exception.NotFoundException;
import com.bss.quote.repository.LeadRoutingRuleRepository;
import com.bss.quote.repository.LeadScoringRuleRepository;
import com.bss.quote.repository.OpportunityActivityRepository;
import com.bss.quote.repository.OpportunityItemRepository;
import com.bss.quote.repository.OpportunityStageHistoryRepository;
import com.bss.quote.repository.PipelineSnapshotRepository;
import com.bss.quote.repository.SalesLeadRepository;
import com.bss.quote.repository.SalesOpportunityRepository;
import com.bss.quote.repository.SalesQuotaRepository;
import com.bss.quote.security.TenantContext;
import com.bss.quote.security.TenantRegistry;
import com.bss.quote.security.TenantScope;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private final OpportunityStageHistoryRepository stageHistory;
    private final LeadScoringRuleRepository scoringRules;
    private final LeadRoutingRuleRepository routingRules;
    private final SalesQuotaRepository quotaRepo;
    private final PipelineSnapshotRepository snapshots;
    private final QuoteService quotes;
    private final DomainEventPublisher events;
    private final TenantScope tenantScope;
    private final TenantRegistry tenants;
    private final RestClient socialClient;
    private final int hotScore;
    private final int warmScore;

    public SalesService(SalesLeadRepository leads, SalesOpportunityRepository opportunities,
            OpportunityItemRepository items, OpportunityActivityRepository activities,
            OpportunityStageHistoryRepository stageHistory,
            LeadScoringRuleRepository scoringRules,
            LeadRoutingRuleRepository routingRules,
            SalesQuotaRepository quotaRepo,
            PipelineSnapshotRepository snapshots,
            QuoteService quotes, DomainEventPublisher events, TenantScope tenantScope,
            TenantRegistry tenants,
            RestClient.Builder builder,
            @Value("${bss.sales.lead-hot-score:70}") int hotScore,
            @Value("${bss.sales.lead-warm-score:40}") int warmScore) {
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
    public SocialImport importSocial() {
        String tenantId = tenantScope.currentTenantId();
        TenantRegistry.TenantEntry tenant = tenants.byId(tenantId);
        if (tenant == null || tenant.getSocialApiUrl() == null || tenant.getSocialApiUrl().isBlank()
                || tenant.getSocialLeadFormId() == null || tenant.getSocialLeadFormId().isBlank()) {
            throw new BadRequestException(
                    "no social lead form is configured for this tenant — the seam is per-tenant");
        }
        JsonNode response = socialClient.get()
                .uri(tenant.socialBase() + "/" + tenant.getSocialLeadFormId() + "/leads"
                        + (tenant.isMeta() ? "?fields=id,created_time,field_data&limit=100" : ""))
                .header("Authorization", "Bearer " + tenant.getSocialAccessToken())
                .retrieve().body(JsonNode.class);
        int imported = 0;
        int seen = 0;
        if (response != null && response.path("data").isArray()) {
            JsonNode entries = response.get("data");
            seen = entries.size();
            for (JsonNode entry : entries) {
                if (entry.isObject() && importOne(entry, tenantId)) {
                    imported++;
                }
            }
        }
        log.info("social lead import for '{}': {} entries at the platform, {} new", tenantId, seen, imported);
        return new SocialImport(tenant.getSocialLeadFormId(), seen, imported);
    }

    /** One platform entry (Meta Lead Ads shape: id + field_data[{name, values[]}]) → a salesLead. */
    private boolean importOne(JsonNode entry, String tenantId) {
        String socialRef = text(entry.path("id"));
        if (socialRef == null || leads.existsByTenantIdAndSocialRef(tenantId, socialRef)) {
            return false;
        }
        Map<String, String> fields = new LinkedHashMap<>();
        for (JsonNode field : entry.path("field_data")) {
            JsonNode values = field.path("values");
            if (field.hasNonNull("name") && values.isArray() && values.size() > 0) {
                fields.put(field.get("name").asText(), values.get(0).asText());
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
        LeadView created = LeadView.of(leads.save(lead));
        events.publish("SalesLeadCreateEvent", "salesLead", created);
        return true;
    }

    /** Anyone may knock: the capture endpoint is open (the tenant comes
     * from the verified token or the gateway's hostname mapping). */
    @Transactional
    public LeadView createLead(LeadRequest dto) {
        if (dto.name() == null || dto.name().isBlank()) {
            throw new BadRequestException("name is required — what is the lead about?");
        }
        SalesLead lead = new SalesLead();
        String id = UUID.randomUUID().toString();
        lead.setId(id);
        lead.setTenantId(tenantScope.currentTenantId());
        lead.setHref(ApiConstants.SALES_BASE + "/salesLead/" + id);
        lead.setName(truncate(dto.name(), 255));
        lead.setDescription(dto.description() == null ? null : truncate(dto.description(), 2000));
        lead.setContactName(str(dto.contactName()));
        lead.setContactEmail(str(dto.contactEmail()));
        lead.setCompany(str(dto.company()));
        lead.setSource(dto.source() == null ? "storefront" : str(dto.source()));
        lead.setState(SalesLead.ACKNOWLEDGED);
        if (dto.companySize() != null) lead.setCompanySize(dto.companySize());
        scoreAndRoute(lead);
        lead.setCreatedAt(OffsetDateTime.now());
        lead.setLastUpdate(OffsetDateTime.now());
        LeadView created = LeadView.of(leads.save(lead));
        events.publish("SalesLeadCreateEvent", "salesLead", created);
        log.info("sales lead '{}' acknowledged (source: {}, score: {}, grade: {}, owner: {})",
                lead.getName(), lead.getSource(), lead.getScore(), lead.getGrade(), lead.getOwnerName());
        return created;
    }

    @Transactional(readOnly = true)
    public List<LeadView> findLeads() {
        return leads.findByTenantIdOrderByCreatedAtDesc(tenantScope.currentTenantId())
                .stream().map(LeadView::of).toList();
    }

    // ---------------- O2: lead scoring + routing ----------------

    /** Score a lead from the tenant's scoring rules, grade it, and route it to
     *  an owner. Runs on capture; the opportunity later inherits the owner. */
    private void scoreAndRoute(SalesLead lead) {
        List<LeadScoringRule> rules = scoringRules.findByTenantIdOrderByCreatedAt(lead.getTenantId());
        // Pull the CDP lead signal once, only if an engagement rule exists and
        // we have an email to look up (fail-soft — the CDP never blocks capture).
        LeadSignal signal = null;
        boolean needsSignal = lead.getContactEmail() != null
                && rules.stream().anyMatch(r -> LeadScoringRule.ENGAGEMENT.equals(r.getField()));
        if (needsSignal) {
            try { signal = quotes.leadSignal(lead.getContactEmail()); }
            catch (Exception e) { signal = null; /* CDP unreachable — skip the signal */ }
        }
        final LeadSignal sig = signal == null ? LeadSignal.NONE : signal;
        int score = 0;
        for (LeadScoringRule r : rules) {
            boolean hit = switch (r.getField()) {
                case LeadScoringRule.SOURCE -> r.getValue() != null && r.getValue().equalsIgnoreCase(lead.getSource());
                case LeadScoringRule.COMPANY_PRESENT -> lead.getCompany() != null && !lead.getCompany().isBlank();
                case LeadScoringRule.COMPANY_SIZE_MIN -> lead.getCompanySize() != null
                        && r.getValue() != null && lead.getCompanySize() >= parseIntSafe(r.getValue());
                case LeadScoringRule.KEYWORD -> r.getValue() != null && containsCi(lead.getName(), r.getValue())
                        || (r.getValue() != null && containsCi(lead.getDescription(), r.getValue()));
                case LeadScoringRule.ENGAGEMENT -> engagementHit(sig, r.getValue());
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

    /** Match a CDP lead signal against an engagement rule value. */
    private boolean engagementHit(LeadSignal sig, String value) {
        if (value == null || sig.absent()) return false;
        return switch (value) {
            case "knownProspect" -> Boolean.TRUE.equals(sig.knownProspect());
            case "engaged" -> Boolean.TRUE.equals(sig.engaged());
            default -> value.equalsIgnoreCase(sig.engagement()); // opened|clicked
        };
    }

    private int parseIntSafe(String s) {
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return Integer.MAX_VALUE; }
    }

    /** Recompute a lead's score/grade/owner (e.g. after the rules changed). */
    @Transactional
    public LeadView rescoreLead(String id) {
        SalesLead lead = requireLead(id);
        scoreAndRoute(lead);
        lead.setLastUpdate(OffsetDateTime.now());
        return LeadView.of(leads.save(lead));
    }

    @Transactional
    public ScoringRuleView createScoringRule(ScoringRuleRequest dto) {
        String field = str(dto.field());
        if (!List.of(LeadScoringRule.SOURCE, LeadScoringRule.COMPANY_PRESENT,
                LeadScoringRule.COMPANY_SIZE_MIN, LeadScoringRule.KEYWORD,
                LeadScoringRule.ENGAGEMENT).contains(field)) {
            throw new BadRequestException("field must be source/companyPresent/companySizeMin/keyword/engagement");
        }
        LeadScoringRule r = new LeadScoringRule();
        r.setId(UUID.randomUUID().toString());
        r.setTenantId(tenantScope.currentTenantId());
        r.setField(field);
        r.setValue(str(dto.value()));
        r.setPoints(dto.points() == null ? 0 : dto.points());
        r.setCreatedAt(OffsetDateTime.now());
        scoringRules.save(r);
        return ScoringRuleView.of(r);
    }

    @Transactional(readOnly = true)
    public List<ScoringRuleView> listScoringRules() {
        return scoringRules.findByTenantIdOrderByCreatedAt(tenantScope.currentTenantId())
                .stream().map(ScoringRuleView::of).toList();
    }

    @Transactional
    public RoutingRuleView createRoutingRule(RoutingRuleRequest dto) {
        if (dto.assignee() == null) throw new BadRequestException("assignee is required");
        LeadRoutingRule r = new LeadRoutingRule();
        r.setId(UUID.randomUUID().toString());
        r.setTenantId(tenantScope.currentTenantId());
        r.setMinScore(dto.minScore() == null ? 0 : dto.minScore());
        r.setAssignee(str(dto.assignee()));
        r.setCreatedAt(OffsetDateTime.now());
        routingRules.save(r);
        return RoutingRuleView.of(r);
    }

    @Transactional(readOnly = true)
    public List<RoutingRuleView> listRoutingRules() {
        return routingRules.findByTenantIdOrderByMinScoreDesc(tenantScope.currentTenantId())
                .stream().map(RoutingRuleView::of).toList();
    }

    @Transactional(readOnly = true)
    public LeadView findLead(String id) {
        return LeadView.of(requireLead(id));
    }

    /**
     * The lead's one decision: QUALIFIED (mints the opportunity — the
     * SPANCO step from suspect to prospect-with-a-deal) or UNQUALIFIED.
     * Either way the decision is final; leads are not re-litigated.
     */
    @Transactional
    public LeadView patchLead(String id, LeadPatch patch) {
        SalesLead lead = requireLead(id);
        String state = str(patch.state());
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
            opp.setPartyId(str64(patch.partyId()));
            opp.setCreatedAt(OffsetDateTime.now());
            opp.setLastUpdate(OffsetDateTime.now());
            opportunities.save(opp);
            recordStage(opp, SalesOpportunity.QUALIFICATION);
            lead.setOpportunityId(oppId);
            events.publish("SalesOpportunityCreateEvent", "salesOpportunity", toView(opp));
            logActivityInternal(opp, OpportunityActivity.LIFECYCLE,
                    "Qualified from lead — opportunity opened at Qualification");
            log.info("lead '{}' qualified into opportunity {}", lead.getName(), oppId);
        }
        return LeadView.of(leads.save(lead));
    }

    @Transactional(readOnly = true)
    public List<OpportunityView> findOpportunities() {
        return opportunities.findByTenantIdOrderByCreatedAtDesc(tenantScope.currentTenantId())
                .stream().map(this::toView).toList();
    }

    @Transactional(readOnly = true)
    public OpportunityView findOpportunity(String id) {
        return toView(requireOpportunity(id));
    }

    /**
     * Work the deal: move it along the pipeline, set its value/close date/owner,
     * or close it won (ideally with the quote that sealed it) or lost. A closed
     * deal stays closed. Only the fields present in the patch change.
     */
    @Transactional
    public OpportunityView patchOpportunity(String id, OpportunityPatch patch) {
        SalesOpportunity opp = requireOpportunity(id);
        if (!SalesOpportunity.DEVELOPED.equals(opp.getState())) {
            throw new BadRequestException("this opportunity is already " + opp.getState()
                    + " — closed deals stay closed");
        }
        List<String> beats = new ArrayList<>();

        // --- close: state won/lost, via explicit state or a closed stage ---
        String state = str(patch.state());
        String stage = str(patch.stage());
        boolean closeWon = SalesOpportunity.WON.equals(state) || SalesOpportunity.CLOSED_WON.equals(stage);
        boolean closeLost = SalesOpportunity.LOST.equals(state) || SalesOpportunity.CLOSED_LOST.equals(stage);
        if (closeWon || closeLost) {
            opp.setState(closeWon ? SalesOpportunity.WON : SalesOpportunity.LOST);
            opp.setStage(closeWon ? SalesOpportunity.CLOSED_WON : SalesOpportunity.CLOSED_LOST);
            opp.setProbability(closeWon ? 100 : 0);
            opp.setForecastCategory(closeWon ? SalesOpportunity.CAT_CLOSED : SalesOpportunity.CAT_OMITTED);
            opp.setStageChangedAt(OffsetDateTime.now());
            recordStage(opp, opp.getStage());
            if (patch.closeReason() != null) opp.setCloseReason(str(patch.closeReason()));
            if (patch.quote() != null && patch.quote().id() != null) {
                opp.setQuoteRef(patch.quote().id());
            }
            opp.setLastUpdate(OffsetDateTime.now());
            OpportunityView closed = toView(opportunities.save(opp));
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
        if (patch.forecastCategory() != null) {
            String c = str(patch.forecastCategory());
            if (!SalesOpportunity.isForecastCategory(c)) {
                throw new BadRequestException("forecastCategory must be pipeline/bestCase/commit/closed/omitted");
            }
            opp.setForecastCategory(c);
        }
        if (patch.probability() != null) opp.setProbability(patch.probability());
        if (patch.amount() != null) opp.setAmount(patch.amount());
        if (patch.currency() != null) opp.setCurrency(str(patch.currency()));
        if (patch.expectedCloseDate() != null) {
            opp.setExpectedCloseDate(LocalDate.parse(str(patch.expectedCloseDate())));
        }
        if (patch.ownerId() != null) opp.setOwnerId(str64(patch.ownerId()));
        if (patch.ownerName() != null) opp.setOwnerName(str(patch.ownerName()));
        if (patch.partyId() != null) opp.setPartyId(str64(patch.partyId()));
        if (patch.description() != null) {
            opp.setDescription(truncate(patch.description(), 2000));
        }
        opp.setLastUpdate(OffsetDateTime.now());
        OpportunityView updated = toView(opportunities.save(opp));
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
    public OpportunityView addItem(String opportunityId, LineItem dto) {
        SalesOpportunity opp = requireOpportunity(opportunityId);
        if (dto.offeringName() == null || dto.offeringName().isBlank()) {
            throw new BadRequestException("offeringName is required — what product is on the deal?");
        }
        OpportunityItem item = new OpportunityItem();
        String itemId = UUID.randomUUID().toString();
        item.setId(itemId);
        item.setTenantId(opp.getTenantId());
        item.setOpportunityId(opp.getId());
        item.setOfferingId(str64(dto.offeringId()));
        item.setOfferingName(str(dto.offeringName()));
        item.setQuantity(Math.max(1, dto.quantityOrOne()));
        item.setUnitPrice(dto.unitPriceOrZero());
        item.setCurrency(opp.getCurrency() == null ? DEFAULT_CURRENCY : opp.getCurrency());
        item.setRecurring(dto.isRecurring());
        item.setCreatedAt(OffsetDateTime.now());
        items.save(item);
        recomputeAmount(opp);
        logActivityInternal(opp, OpportunityActivity.NOTE,
                "Added " + item.getQuantity() + "× " + item.getOfferingName() + " to the deal");
        return toView(requireOpportunity(opportunityId));
    }

    @Transactional
    public OpportunityView removeItem(String opportunityId, String itemId) {
        SalesOpportunity opp = requireOpportunity(opportunityId);
        OpportunityItem item = items.findByIdAndTenantId(itemId, opp.getTenantId())
                .orElseThrow(() -> NotFoundException.forResource("OpportunityItem", itemId));
        items.delete(item);
        recomputeAmount(opp);
        return toView(requireOpportunity(opportunityId));
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
    public ActivityLog logActivity(String opportunityId, ActivityRequest dto) {
        SalesOpportunity opp = requireOpportunity(opportunityId);
        if (dto.note() == null || dto.note().isBlank()) {
            throw new BadRequestException("note is required — what happened, or what's next?");
        }
        String type = str(dto.type());
        if (type == null) type = dto.dueDate() != null ? OpportunityActivity.NEXT_STEP : OpportunityActivity.NOTE;
        OffsetDateTime due = dto.dueDate() == null ? null : OffsetDateTime.parse(str(dto.dueDate()));
        String status = due != null ? OpportunityActivity.OPEN : OpportunityActivity.DONE;
        logActivityInternal(opp, type, truncate(dto.note(), 2000), due, status, str(dto.assignee()));
        return new ActivityLog(opportunityId, activityLog(opp));
    }

    /** Mark an open task done. */
    @Transactional
    public ActivityView completeTask(String opportunityId, String activityId) {
        SalesOpportunity opp = requireOpportunity(opportunityId);
        OpportunityActivity a = activities.findByTenantIdAndOpportunityIdOrderByOccurredAtDesc(
                        opp.getTenantId(), opportunityId).stream()
                .filter(x -> x.getId().equals(activityId)).findFirst()
                .orElseThrow(() -> NotFoundException.forResource("OpportunityActivity", activityId));
        a.setStatus(OpportunityActivity.DONE);
        activities.save(a);
        return ActivityView.of(a);
    }

    /** The open-tasks queue — every open next-step across the pipeline, soonest
     *  due first; optionally just one assignee's. Overdue ones are flagged. */
    @Transactional(readOnly = true)
    public OpenTasks openTasks(String assignee) {
        String tenantId = tenantScope.currentTenantId();
        List<OpportunityActivity> open = assignee == null
                ? activities.findByTenantIdAndStatusOrderByDueDateAsc(tenantId, OpportunityActivity.OPEN)
                : activities.findByTenantIdAndStatusAndAssigneeOrderByDueDateAsc(
                        tenantId, OpportunityActivity.OPEN, assignee);
        OffsetDateTime now = OffsetDateTime.now();
        List<TaskView> tasks = new ArrayList<>();
        for (OpportunityActivity a : open) {
            tasks.add(TaskView.of(a, now));
        }
        return OpenTasks.of(tasks);
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
        // mint a TMF683 touchpoint — sales on the customer 360. Event-only
        // shape: no public method returns it.
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
    public QuoteHandoff buildQuote(String opportunityId) {
        SalesOpportunity opp = requireOpportunity(opportunityId);
        List<OpportunityItem> lines = items.findByTenantIdAndOpportunityIdOrderByCreatedAt(
                opp.getTenantId(), opp.getId());
        if (lines.isEmpty()) {
            throw new BadRequestException("add line items to the opportunity before quoting");
        }
        List<LineItem> lineItems = new ArrayList<>();
        for (OpportunityItem l : lines) {
            lineItems.add(new LineItem(l.getOfferingId(), l.getOfferingName(), l.getQuantity(),
                    l.getUnitPrice(), l.isRecurring()));
        }
        QuoteView quote = quotes.createFromLineItems(
                "Quote for " + opp.getName(), opp.getPartyId(), opp.getCurrency(), lineItems);
        opp.setQuoteRef(quote.id());
        opp.setLastUpdate(OffsetDateTime.now());
        opportunities.save(opp);
        logActivityInternal(opp, OpportunityActivity.LIFECYCLE,
                "Quote generated from the deal's line items");
        return new QuoteHandoff(toView(requireOpportunity(opportunityId)), quote);
    }

    /** Guided selling → deal: run the answers through the recommendation rules
     *  and add the recommended offerings to the opportunity as line items. */
    @Transactional
    public GuidedApplied applyGuided(String opportunityId, JsonNode answers) {
        requireOpportunity(opportunityId);
        List<Recommendation> recs = quotes.recommend(answers).recommendations();
        for (Recommendation r : recs) {
            // guided sizing picks products; the rep prices them
            addItem(opportunityId, new LineItem(null, r.offeringName(), r.quantity(), BigDecimal.ZERO, null));
        }
        return new GuidedApplied(recs.size(), recs, toView(requireOpportunity(opportunityId)));
    }

    // ---------------- O2: quota + attainment ----------------

    @Transactional
    public QuotaView createQuota(QuotaRequest dto) {
        if (dto.ownerName() == null || dto.quotaPeriod() == null || dto.amount() == null) {
            throw new BadRequestException("ownerName, quotaPeriod (YYYY-MM) and amount are required");
        }
        SalesQuota q = new SalesQuota();
        q.setId(UUID.randomUUID().toString());
        q.setTenantId(tenantScope.currentTenantId());
        q.setOwnerName(str(dto.ownerName()));
        q.setQuotaPeriod(str(dto.quotaPeriod()));
        q.setAmount(dto.amount());
        q.setTeam(str(dto.team()));
        q.setCreatedAt(OffsetDateTime.now());
        quotaRepo.save(q);
        return QuotaView.of(q);
    }

    @Transactional(readOnly = true)
    public List<QuotaView> listQuotas() {
        return quotaRepo.findByTenantIdOrderByCreatedAt(tenantScope.currentTenantId())
                .stream().map(QuotaView::of).toList();
    }

    /** Quota attainment for a period: per owner, the quota vs won-in-period vs
     *  the probability-weighted open forecast (coverage) — the VP's Monday view. */
    @Transactional(readOnly = true)
    public QuotaAttainment quotaAttainment(String period) {
        String tenantId = tenantScope.currentTenantId();
        List<SalesQuota> quotas = quotaRepo.findByTenantIdAndQuotaPeriodOrderByOwnerName(tenantId, period);
        List<SalesOpportunity> opps = opportunities.findByTenantIdOrderByCreatedAtDesc(tenantId);
        List<OwnerRow> rows = new ArrayList<>();
        for (SalesQuota q : quotas) {
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
            rows.add(new OwnerRow(q.getOwnerName(), q.getTeam(), quota, won, weightedOpen,
                    pct(won, quota), pct(won.add(weightedOpen), quota)));
        }
        // Team roll-up: owners aggregate to their team (quota, won, weighted).
        Map<String, BigDecimal[]> teamAgg = new LinkedHashMap<>();
        for (OwnerRow r : rows) {
            String team = r.team() == null ? "(no team)" : r.team();
            BigDecimal[] a = teamAgg.computeIfAbsent(team,
                    k -> new BigDecimal[] { BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO });
            a[0] = a[0].add(r.quota());
            a[1] = a[1].add(r.won());
            a[2] = a[2].add(r.weightedOpen());
        }
        List<TeamRow> byTeam = new ArrayList<>();
        for (Map.Entry<String, BigDecimal[]> e : teamAgg.entrySet()) {
            BigDecimal[] a = e.getValue();
            byTeam.add(new TeamRow(e.getKey(), a[0], a[1], a[2], pct(a[1], a[0]), pct(a[1].add(a[2]), a[0])));
        }
        return new QuotaAttainment(period, rows, byTeam);
    }

    // ---------------- O2: weekly pipeline snapshot (forecast-over-time) ----------------

    /** Capture the current open weighted forecast for the acting tenant. */
    @Transactional
    public SnapshotView captureSnapshot() {
        PipelineBoard p = pipeline();
        PipelineSnapshot s = new PipelineSnapshot();
        s.setId(UUID.randomUUID().toString());
        s.setTenantId(tenantScope.currentTenantId());
        s.setCapturedAt(OffsetDateTime.now());
        s.setOpenCount(p.openCount());
        s.setOpenAmount(p.openAmount());
        s.setWeightedForecast(p.weightedForecast());
        s.setCurrency(p.currency());
        snapshots.save(s);
        return SnapshotView.of(s);
    }

    @Transactional(readOnly = true)
    public List<SnapshotView> listSnapshots() {
        return snapshots.findTop52ByTenantIdOrderByCapturedAtDesc(tenantScope.currentTenantId())
                .stream().map(SnapshotView::of).toList();
    }

    /** The weekly scheduled capture — one snapshot per tenant, so forecast
     *  over time and slippage are visible without anyone remembering to click. */
    @Scheduled(cron = "${bss.sales.snapshot-cron:0 0 6 * * MON}")
    public void weeklySnapshot() {
        for (TenantRegistry.TenantEntry t : tenants.getRegistry()) {
            if (t.getId() == null) continue;
            try (TenantContext ignored = TenantContext.actAs(t.getId())) {
                captureSnapshot();
            } catch (Exception e) {
                log.warn("weekly pipeline snapshot failed for tenant {}: {}", t.getId(), e.getMessage());
            }
        }
    }

    private double pct(BigDecimal num, BigDecimal denom) {
        if (denom == null || denom.signum() == 0) return 0;
        return Math.round(num.multiply(BigDecimal.valueOf(1000)).divide(denom, RoundingMode.HALF_UP)
                .doubleValue()) / 10.0;
    }

    private String yearMonth(OffsetDateTime t) {
        return String.format("%04d-%02d", t.getYear(), t.getMonthValue());
    }

    // ---------------- pipeline & forecast ----------------

    /** The board: open deals grouped by stage with count, value, and the
     *  probability-weighted forecast — the number a sales manager commits. */
    @Transactional(readOnly = true)
    public PipelineBoard pipeline() {
        String tenantId = tenantScope.currentTenantId();
        List<SalesOpportunity> all = opportunities.findByTenantIdOrderByCreatedAtDesc(tenantId);
        String[] order = { SalesOpportunity.QUALIFICATION, SalesOpportunity.NEEDS_ANALYSIS,
                SalesOpportunity.PROPOSAL, SalesOpportunity.NEGOTIATION };
        List<StageColumn> stages = new ArrayList<>();
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
            stages.add(new StageColumn(st, count, amt, wtd));
            openCount += count;
            openAmount = openAmount.add(amt);
            weighted = weighted.add(wtd);
        }
        // Forecast categories: the number a manager commits, rolled up over the
        // open pipeline (Commit is the "will land" number; Best Case is upside).
        String[] cats = { SalesOpportunity.CAT_PIPELINE, SalesOpportunity.CAT_BEST_CASE,
                SalesOpportunity.CAT_COMMIT };
        List<CategoryColumn> byCategory = new ArrayList<>();
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
            byCategory.add(new CategoryColumn(cat, count, amt));
        }
        return new PipelineBoard(stages, byCategory, openCount, openAmount, weighted, DEFAULT_CURRENCY);
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
    public FunnelReport funnel() {
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
            Set<String> stagesSeen = new HashSet<>();
            for (int i = 0; i < hist.size(); i++) {
                String st = hist.get(i).getStage();
                if (reached.containsKey(st)) stagesSeen.add(st);
                // dwell = time until the next transition (only for exited stages)
                if (i + 1 < hist.size()) {
                    long secs = Duration.between(
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
                cycleSecondsTotal += Math.max(0, Duration.between(
                        created.get(e.getKey()), close.getEnteredAt()).getSeconds());
                cycleCount++;
            }
        }

        // Stage-to-stage conversion.
        List<StageConversion> conversion = new ArrayList<>();
        for (int i = 0; i < order.length - 1; i++) {
            int from = reached.get(order[i]);
            int to = reached.get(order[i + 1]);
            conversion.add(new StageConversion(order[i], order[i + 1], from, to,
                    from == 0 ? 0 : Math.round(to * 1000.0 / from) / 10.0));
        }
        // Average time-in-stage (days).
        List<TimeInStage> timeInStage = new ArrayList<>();
        for (String s : new String[] { SalesOpportunity.QUALIFICATION, SalesOpportunity.NEEDS_ANALYSIS,
                SalesOpportunity.PROPOSAL, SalesOpportunity.NEGOTIATION }) {
            long[] acc = stageDwell.getOrDefault(s, new long[2]);
            double days = acc[1] == 0 ? 0 : Math.round(acc[0] / (double) acc[1] / 86400.0 * 10) / 10.0;
            timeInStage.add(new TimeInStage(s, days));
        }

        int won = (int) opps.stream().filter(o -> SalesOpportunity.WON.equals(o.getState())).count();
        int lost = (int) opps.stream().filter(o -> SalesOpportunity.LOST.equals(o.getState())).count();
        double winRate = (won + lost) == 0 ? 0 : Math.round(won * 1000.0 / (won + lost)) / 10.0;
        double avgCycleDays = cycleCount == 0 ? 0 : Math.round(cycleSecondsTotal / (double) cycleCount / 86400.0 * 10) / 10.0;

        // A plain-language summary — the copilot narrates from this, and it
        // keeps us honest about thin samples.
        String weakest = conversion.stream()
                .filter(c -> c.reachedFrom() > 0)
                .min(Comparator.comparingDouble(StageConversion::conversionPct))
                .map(c -> c.from() + "→" + c.to()).orElse("n/a");
        String summary = "Win rate " + winRate + "% over " + (won + lost) + " closed deals; "
                + "average cycle " + avgCycleDays + " days. Weakest stage transition: " + weakest + "."
                + ((won + lost) < 5 ? " (Small sample — read as a hint, not a measurement.)" : "");

        return new FunnelReport(conversion, timeInStage, winRate, won, lost, avgCycleDays, summary);
    }

    /** Which programme sourced the revenue: won deals grouped by their lead's
     *  source, with the closed amount — the honest B2B attribution number. */
    @Transactional(readOnly = true)
    public WonReport wonReport() {
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
        List<SourceRow> bySource = new ArrayList<>();
        for (String s : amountBySource.keySet()) {
            bySource.add(new SourceRow(s, countBySource.get(s)[0], amountBySource.get(s)));
        }
        return new WonReport(won, total, bySource, DEFAULT_CURRENCY);
    }

    private SalesLead requireLead(String id) {
        return leads.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource("SalesLead", id));
    }

    private SalesOpportunity requireOpportunity(String id) {
        return opportunities.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource("SalesOpportunity", id));
    }

    private String str(String v) {
        return v == null ? null : truncate(v, 255);
    }

    private String str64(String v) {
        return v == null ? null : truncate(v, 64);
    }

    private String truncate(String v, int max) {
        return v.length() > max ? v.substring(0, max) : v;
    }

    /** A node's text, or null when it is absent or JSON null. */
    private static String text(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? null : node.asText();
    }

    /** The deal's activity log, newest first. */
    private List<ActivityView> activityLog(SalesOpportunity opp) {
        return activities.findByTenantIdAndOpportunityIdOrderByOccurredAtDesc(opp.getTenantId(), opp.getId())
                .stream().map(ActivityView::of).toList();
    }

    /** The deal with its composition and its workspace, for the console detail. */
    private OpportunityView toView(SalesOpportunity opp) {
        List<OpportunityItemView> lines = items.findByTenantIdAndOpportunityIdOrderByCreatedAt(
                opp.getTenantId(), opp.getId()).stream().map(OpportunityItemView::of).toList();
        return OpportunityView.of(opp, lines, activityLog(opp));
    }
}
