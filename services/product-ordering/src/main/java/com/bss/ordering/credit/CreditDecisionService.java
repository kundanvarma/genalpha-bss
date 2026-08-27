package com.bss.ordering.credit;

import com.bss.ordering.entity.CreditDecision;
import com.bss.ordering.events.DomainEventPublisher;
import com.bss.ordering.repository.CreditDecisionRepository;
import com.bss.ordering.security.TenantScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Orchestrates the credit port for checkout: enabled per tenant (default ON
 * for dev — flip {@code bss.credit.enabled} or scope with
 * {@code bss.credit.tenants}), FAIL-OPEN on driver errors (the house
 * default: an assessment outage must never block commerce), and every
 * answered assessment leaves a decision record + a
 * CreditDecisionRecordedEvent — in its OWN transaction, so a frozen
 * rejection that rolls the order back still keeps its audit trail.
 */
@Service
public class CreditDecisionService {

    private static final Logger log = LoggerFactory.getLogger(CreditDecisionService.class);

    private final CreditDecisionPort port;
    private final CreditDecisionRepository repository;
    private final DomainEventPublisher events;
    private final TenantScope tenantScope;
    private final boolean enabled;
    private final Set<String> enabledTenants;
    private final String purpose;

    public CreditDecisionService(CreditDecisionPort port, CreditDecisionRepository repository,
            DomainEventPublisher events, TenantScope tenantScope,
            @Value("${bss.credit.enabled:true}") boolean enabled,
            @Value("${bss.credit.tenants:}") String tenants,
            @Value("${bss.credit.purpose:postpaid-signup}") String purpose) {
        this.port = port;
        this.repository = repository;
        this.events = events;
        this.tenantScope = tenantScope;
        this.enabled = enabled;
        this.enabledTenants = tenants == null || tenants.isBlank() ? Set.of()
                : Set.of(tenants.split("\\s*,\\s*"));
        this.purpose = purpose;
    }

    /** Enabled globally, and (when a tenant list is set) for THIS tenant. */
    public boolean enabledForCurrentTenant() {
        return enabled && (enabledTenants.isEmpty()
                || enabledTenants.contains(tenantScope.currentTenantId()));
    }

    /**
     * Assess the ordering party — the opaque reference handed to the driver
     * is the party id; a real bureau driver resolves it to a national id
     * inside its own walls. Returns null when disabled or on driver failure
     * (fail-open). The CALLER records the answer via {@link #record} (a
     * separate proxy call, so its REQUIRES_NEW transaction really opens)
     * before acting on it.
     */
    public CreditDecisionPort.Decision assessParty(String partyId) {
        if (!enabledForCurrentTenant() || partyId == null) {
            return null;
        }
        try {
            return port.assess(partyId, purpose);
        } catch (Exception e) {
            log.warn("credit bureau unreachable, order proceeds unassessed (fail-open): {}",
                    e.getMessage());
            return null;
        }
    }

    /**
     * REQUIRES_NEW on purpose: the record (and its outbox event) must
     * survive the order transaction rolling back on a frozen/declined
     * rejection — the audit trail IS the point of storing decisions.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String partyId, CreditDecisionPort.Decision decision) {
        CreditDecision row = new CreditDecision();
        row.setId(UUID.randomUUID().toString());
        row.setTenantId(tenantScope.currentTenantId());
        row.setPartyId(partyId);
        row.setDecision(decision.decision());
        row.setScoreBand(decision.scoreBand());
        row.setRemarksPresent(decision.remarksPresent());
        row.setPurpose(purpose);
        row.setDecidedAt(OffsetDateTime.now());
        repository.save(row);

        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("id", row.getId());
        resource.put("partyId", partyId);
        resource.put("decision", decision.decision());
        if (decision.scoreBand() != null) {
            resource.put("scoreBand", decision.scoreBand());
        }
        resource.put("remarksPresent", decision.remarksPresent());
        resource.put("purpose", purpose);
        resource.put("decidedAt", row.getDecidedAt().toString());
        resource.put("relatedParty", List.of(Map.of("id", partyId, "role", "customer")));
        events.publish("CreditDecisionRecordedEvent", "creditDecision", resource);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> decisionsFor(String partyId) {
        return repository
                .findByTenantIdAndPartyIdOrderByDecidedAtDesc(tenantScope.currentTenantId(), partyId)
                .stream().map(row -> {
                    Map<String, Object> out = new LinkedHashMap<String, Object>();
                    out.put("id", row.getId());
                    out.put("partyId", row.getPartyId());
                    out.put("decision", row.getDecision());
                    if (row.getScoreBand() != null) {
                        out.put("scoreBand", row.getScoreBand());
                    }
                    out.put("remarksPresent", row.isRemarksPresent());
                    out.put("purpose", row.getPurpose());
                    out.put("decidedAt", row.getDecidedAt().toString());
                    return out;
                }).toList();
    }
}
