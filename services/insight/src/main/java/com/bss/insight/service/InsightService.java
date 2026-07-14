package com.bss.insight.service;

import com.bss.insight.client.PolicyClient;
import com.bss.insight.entity.VisitorEvent;
import com.bss.insight.entity.VisitorProfile;
import com.bss.insight.exception.BadRequestException;
import com.bss.insight.exception.NotFoundException;
import com.bss.insight.repository.VisitorEventRepository;
import com.bss.insight.repository.VisitorProfileRepository;
import com.bss.insight.security.TenantScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The first-party profile, consent first: NOTHING is stored before the
 * visitor says yes, a rejecting visitor generates no rows at all, and the
 * visitor->party stitch happens only under personalization consent. The
 * decisioning goes through policy rules-as-data with a coded default —
 * "show them more of what they looked at."
 */
@Service
public class InsightService {

    private final VisitorProfileRepository profiles;
    private final VisitorEventRepository events;
    private final PolicyClient policy;
    private final TenantScope tenantScope;
    private final com.bss.insight.client.AnalyticsForwarder analytics;

    public InsightService(VisitorProfileRepository profiles, VisitorEventRepository events,
            PolicyClient policy, TenantScope tenantScope,
            com.bss.insight.client.AnalyticsForwarder analytics) {
        this.profiles = profiles;
        this.events = events;
        this.policy = policy;
        this.tenantScope = tenantScope;
        this.analytics = analytics;
    }

    /** The consent choice — the only write allowed for an unknown visitor.
     * Revoking analytics consent DELETES the breadcrumbs already held. */
    @Transactional
    public Map<String, Object> consent(String visitorId, boolean analytics, boolean personalization) {
        requireVisitor(visitorId);
        String tenantId = tenantScope.currentTenantId();
        VisitorProfile p = profiles.findByTenantIdAndVisitorId(tenantId, visitorId)
                .orElseGet(() -> {
                    VisitorProfile fresh = new VisitorProfile();
                    fresh.setId(UUID.randomUUID().toString());
                    fresh.setTenantId(tenantId);
                    fresh.setVisitorId(visitorId);
                    fresh.setCreatedAt(OffsetDateTime.now());
                    return fresh;
                });
        p.setAnalyticsConsent(analytics);
        p.setPersonalizationConsent(personalization);
        p.setLastUpdate(OffsetDateTime.now());
        profiles.save(p);
        if (!analytics) {
            events.deleteByTenantIdAndVisitorId(tenantId, visitorId);
        }
        return Map.of("visitorId", visitorId, "analytics", analytics,
                "personalization", personalization);
    }

    /** A behavioral breadcrumb — dropped silently without analytics consent.
     * The response never says which, so consent state does not leak. */
    @Transactional
    public void event(String visitorId, String type, String category,
            String offeringId, String utmSource) {
        requireVisitor(visitorId);
        String tenantId = tenantScope.currentTenantId();
        VisitorProfile p = profiles.findByTenantIdAndVisitorId(tenantId, visitorId).orElse(null);
        if (p == null || !p.isAnalyticsConsent()) {
            return;
        }
        if (utmSource != null && !utmSource.isBlank()) {
            p.setUtmSource(utmSource);
            p.setLastUpdate(OffsetDateTime.now());
            profiles.save(p);
        }
        if (type == null || type.isBlank()) {
            return;
        }
        VisitorEvent e = new VisitorEvent();
        e.setId(UUID.randomUUID().toString());
        e.setTenantId(tenantId);
        e.setVisitorId(visitorId);
        e.setType(type);
        e.setCategory(category);
        e.setOfferingId(offeringId);
        e.setCreatedAt(OffsetDateTime.now());
        events.save(e);
        // the seam: a ga4 tenant's consented events also reach THEIR property
        analytics.forward(tenantId, visitorId, type, category, offeringId);
    }

    /** The login stitch: this browser's profile belongs to this party now —
     * only under personalization consent, never silently. */
    @Transactional
    public Map<String, Object> stitch(String visitorId, String partyId) {
        requireVisitor(visitorId);
        if (partyId == null || partyId.isBlank()) {
            throw new BadRequestException("stitching needs a signed-in caller");
        }
        String tenantId = tenantScope.currentTenantId();
        VisitorProfile p = profiles.findByTenantIdAndVisitorId(tenantId, visitorId).orElse(null);
        if (p == null || !p.isPersonalizationConsent()) {
            return Map.of("stitched", false);
        }
        p.setPartyId(partyId);
        p.setLastUpdate(OffsetDateTime.now());
        profiles.save(p);
        return Map.of("stitched", true, "partyId", partyId);
    }

    /**
     * "What should this person see?" — the one question channels ask.
     * Without personalization consent the answer is the default page,
     * honestly marked. With it: interests from the breadcrumbs, an
     * operator-authored experience rule if one matches, and a coded
     * fallback — lead with what they looked at most.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> experience(String visitorId) {
        requireVisitor(visitorId);
        String tenantId = tenantScope.currentTenantId();
        Map<String, Object> out = new LinkedHashMap<>();
        VisitorProfile p = profiles.findByTenantIdAndVisitorId(tenantId, visitorId).orElse(null);
        if (p == null || !p.isPersonalizationConsent()) {
            out.put("personalized", false);
            return out;
        }
        List<String> interests = events.interestsOf(tenantId, visitorId).stream()
                .map(row -> String.valueOf(row[0]))
                .limit(5)
                .toList();
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("interests", interests);
        context.put("topInterest", interests.isEmpty() ? "" : interests.get(0));
        context.put("utmSource", p.getUtmSource() == null ? "" : p.getUtmSource());
        context.put("knownCustomer", p.getPartyId() != null);
        Map<String, Object> decision = policy.experience(context).orElse(null);
        out.put("personalized", !interests.isEmpty() || decision != null);
        out.put("interests", interests);
        if (!interests.isEmpty()) {
            out.put("heroCategory", interests.get(0));
        }
        if (decision != null) {
            if (decision.get("banner") != null) {
                out.put("banner", decision.get("banner"));
            }
            if (decision.get("experience") instanceof Map<?, ?> exp) {
                exp.forEach((k, v) -> out.put(String.valueOf(k), v));
            }
            out.put("ruleName", decision.get("ruleName"));
        }
        return out;
    }

    /**
     * The fusion window: a KNOWN customer's interests, merged across every
     * browser they stitched — for the recommendation engine. Consent is
     * embedded in the data itself: only consented breadcrumbs exist, only
     * consented stitches link them.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> partyProfile(String partyId) {
        String tenantId = tenantScope.currentTenantId();
        Map<String, Long> merged = new LinkedHashMap<>();
        for (VisitorProfile p : profiles.findByTenantIdAndPartyId(tenantId, partyId)) {
            if (!p.isPersonalizationConsent()) {
                continue;
            }
            for (Object[] row : events.interestsOf(tenantId, p.getVisitorId())) {
                merged.merge(String.valueOf(row[0]), ((Number) row[1]).longValue(), Long::sum);
            }
        }
        List<String> interests = merged.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .map(Map.Entry::getKey)
                .limit(5)
                .toList();
        return Map.of("partyId", partyId, "interests", interests);
    }

    /** Back-office window (and the E2E's honesty probe): the raw profile. */
    @Transactional(readOnly = true)
    public Map<String, Object> profileOf(String visitorId) {
        String tenantId = tenantScope.currentTenantId();
        VisitorProfile p = profiles.findByTenantIdAndVisitorId(tenantId, visitorId)
                .orElseThrow(() -> new NotFoundException(
                        "VisitorProfile '" + visitorId + "' not found"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("visitorId", p.getVisitorId());
        out.put("partyId", p.getPartyId());
        out.put("analyticsConsent", p.isAnalyticsConsent());
        out.put("personalizationConsent", p.isPersonalizationConsent());
        out.put("utmSource", p.getUtmSource());
        out.put("eventCount", events.countByTenantIdAndVisitorId(tenantId, p.getVisitorId()));
        out.put("interests", events.interestsOf(tenantId, p.getVisitorId()).stream()
                .map(row -> Map.of("category", String.valueOf(row[0]), "views", row[1]))
                .toList());
        return out;
    }

    private static void requireVisitor(String visitorId) {
        if (visitorId == null || visitorId.isBlank() || visitorId.length() > 64) {
            throw new BadRequestException("visitorId is required");
        }
    }
}
