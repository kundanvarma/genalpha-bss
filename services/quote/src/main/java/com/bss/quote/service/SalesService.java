package com.bss.quote.service;

import com.bss.quote.api.ApiConstants;
import com.bss.quote.entity.OpportunityActivity;
import com.bss.quote.entity.OpportunityItem;
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
    private final DomainEventPublisher events;
    private final TenantScope tenantScope;
    private final com.bss.quote.security.TenantRegistry tenants;
    private final org.springframework.web.client.RestClient socialClient;

    public SalesService(SalesLeadRepository leads, SalesOpportunityRepository opportunities,
            OpportunityItemRepository items, OpportunityActivityRepository activities,
            DomainEventPublisher events, TenantScope tenantScope,
            com.bss.quote.security.TenantRegistry tenants,
            org.springframework.web.client.RestClient.Builder builder) {
        this.leads = leads;
        this.opportunities = opportunities;
        this.items = items;
        this.activities = activities;
        this.events = events;
        this.tenantScope = tenantScope;
        this.tenants = tenants;
        this.socialClient = builder.build();
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
        lead.setCreatedAt(OffsetDateTime.now());
        lead.setLastUpdate(OffsetDateTime.now());
        Map<String, Object> created = leadToMap(leads.save(lead));
        events.publish("SalesLeadCreateEvent", "salesLead", created);
        log.info("sales lead '{}' acknowledged (source: {})", lead.getName(), lead.getSource());
        return created;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> findLeads() {
        return leads.findByTenantIdOrderByCreatedAtDesc(tenantScope.currentTenantId())
                .stream().map(this::leadToMap).toList();
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
            opp.setState(SalesOpportunity.DEVELOPED);
            // A qualified lead opens at the first pipeline stage; probability
            // rides with the stage until sales edits it.
            opp.setStage(SalesOpportunity.QUALIFICATION);
            opp.setProbability(SalesOpportunity.defaultProbability(SalesOpportunity.QUALIFICATION));
            opp.setCurrency(DEFAULT_CURRENCY);
            // A deal can be with an account we already know (B2B expansion) —
            // then its activities mirror onto that party's 360.
            opp.setPartyId(str64(patch.get("partyId")));
            opp.setCreatedAt(OffsetDateTime.now());
            opp.setLastUpdate(OffsetDateTime.now());
            opportunities.save(opp);
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
                // Probability rides with the stage unless the deal overrides it.
                opp.setProbability(SalesOpportunity.defaultProbability(stage));
            }
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

    /** Log a call/email/note/next-step on the deal. Mirrors onto the party's
     *  TMF683 360 timeline when the deal is with a known account. */
    @Transactional
    public Map<String, Object> logActivity(String opportunityId, Map<String, Object> dto) {
        SalesOpportunity opp = requireOpportunity(opportunityId);
        if (dto.get("note") == null || String.valueOf(dto.get("note")).isBlank()) {
            throw new BadRequestException("note is required — what happened?");
        }
        String type = str(dto.get("type"));
        if (type == null) type = OpportunityActivity.NOTE;
        logActivityInternal(opp, type, truncate(String.valueOf(dto.get("note")), 2000));
        return Map.of("opportunityId", opportunityId, "activities",
                activities.findByTenantIdAndOpportunityIdOrderByOccurredAtDesc(
                        opp.getTenantId(), opportunityId).stream().map(this::activityToMap).toList());
    }

    private void logActivityInternal(SalesOpportunity opp, String type, String note) {
        OpportunityActivity a = new OpportunityActivity();
        String actId = UUID.randomUUID().toString();
        a.setId(actId);
        a.setTenantId(opp.getTenantId());
        a.setOpportunityId(opp.getId());
        a.setPartyId(opp.getPartyId());
        a.setActivityType(type);
        a.setNote(note);
        a.setOccurredAt(OffsetDateTime.now());
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
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("stages", stages);
        out.put("openCount", openCount);
        out.put("openAmount", openAmount);
        out.put("weightedForecast", weighted);
        out.put("currency", DEFAULT_CURRENCY);
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
        if (opp.getProbability() != null) map.put("probability", opp.getProbability());
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
