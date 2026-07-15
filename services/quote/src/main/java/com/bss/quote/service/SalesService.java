package com.bss.quote.service;

import com.bss.quote.api.ApiConstants;
import com.bss.quote.entity.SalesLead;
import com.bss.quote.entity.SalesOpportunity;
import com.bss.quote.events.DomainEventPublisher;
import com.bss.quote.exception.BadRequestException;
import com.bss.quote.exception.NotFoundException;
import com.bss.quote.repository.SalesLeadRepository;
import com.bss.quote.repository.SalesOpportunityRepository;
import com.bss.quote.security.TenantScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
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

    private final SalesLeadRepository leads;
    private final SalesOpportunityRepository opportunities;
    private final DomainEventPublisher events;
    private final TenantScope tenantScope;

    public SalesService(SalesLeadRepository leads, SalesOpportunityRepository opportunities,
            DomainEventPublisher events, TenantScope tenantScope) {
        this.leads = leads;
        this.opportunities = opportunities;
        this.events = events;
        this.tenantScope = tenantScope;
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
            opp.setCreatedAt(OffsetDateTime.now());
            opp.setLastUpdate(OffsetDateTime.now());
            opportunities.save(opp);
            lead.setOpportunityId(oppId);
            events.publish("SalesOpportunityCreateEvent", "salesOpportunity", oppToMap(opp));
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

    /** An opportunity develops until it is WON (ideally with the quote
     * that sealed it) or LOST; closed deals stay closed. */
    @Transactional
    public Map<String, Object> patchOpportunity(String id, Map<String, Object> patch) {
        SalesOpportunity opp = requireOpportunity(id);
        String state = str(patch.get("state"));
        if (!SalesOpportunity.WON.equals(state) && !SalesOpportunity.LOST.equals(state)) {
            throw new BadRequestException("state must be 'won' or 'lost'");
        }
        if (!SalesOpportunity.DEVELOPED.equals(opp.getState())) {
            throw new BadRequestException("this opportunity is already " + opp.getState()
                    + " — closed deals stay closed");
        }
        opp.setState(state);
        if (patch.get("quote") instanceof Map<?, ?> quote && quote.get("id") != null) {
            opp.setQuoteRef(String.valueOf(quote.get("id")));
        }
        opp.setLastUpdate(OffsetDateTime.now());
        Map<String, Object> updated = oppToMap(opportunities.save(opp));
        events.publish("SalesOpportunityStateChangeEvent", "salesOpportunity", updated);
        return updated;
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

    private String truncate(String v, int max) {
        return v.length() > max ? v.substring(0, max) : v;
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
        if (opp.getQuoteRef() != null) {
            map.put("quote", Map.of("id", opp.getQuoteRef(),
                    "href", ApiConstants.BASE_PATH + "/quote/" + opp.getQuoteRef()));
        }
        map.put("creationDate", opp.getCreatedAt());
        map.put("lastUpdate", opp.getLastUpdate());
        map.put("@type", "SalesOpportunity");
        return map;
    }
}
