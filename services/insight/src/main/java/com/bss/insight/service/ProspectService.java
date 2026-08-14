package com.bss.insight.service;

import com.bss.insight.entity.Prospect;
import com.bss.insight.repository.ProspectRepository;
import com.bss.insight.security.TenantScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Prospect capture + list import — the not-yet-customer side of marketing.
 *
 * <p>Consent is enforced HERE, not wished for: an imported contact defaults to
 * {@code unconsented} unless the import declares a lawful basis. A bought list
 * therefore lands captured but NOT reachable — exactly what GDPR/ePrivacy
 * require. Idempotent per (tenant, email): re-imports update, never duplicate,
 * and NEVER silently upgrade consent (only an explicit lawful basis does that).
 */
@Service
public class ProspectService {

    private final ProspectRepository prospects;
    private final TenantScope tenantScope;

    public ProspectService(ProspectRepository prospects, TenantScope tenantScope) {
        this.prospects = prospects;
        this.tenantScope = tenantScope;
    }

    /** Bulk import (an Excel paste, a purchased list, a social lead-form sync). */
    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String, Object> importBulk(Map<String, Object> body) {
        String tenantId = tenantScope.currentTenantId();
        List<Map<String, Object>> rows = body.get("prospects") instanceof List<?> l
                ? (List<Map<String, Object>>) l : List.of();
        String defaultSource = str(body.get("source"), "import");
        int imported = 0;
        int updated = 0;
        int reachable = 0;
        for (Map<String, Object> r : rows) {
            String email = str(r.get("email"), null);
            if (email == null || email.isBlank()) {
                continue;
            }
            email = email.trim().toLowerCase();
            // Consent is granted ONLY by an explicit lawful basis on the row.
            String lawfulBasis = str(r.get("lawfulBasis"), null);
            boolean consented = lawfulBasis != null && !lawfulBasis.isBlank();
            Prospect p = prospects.findByTenantIdAndEmail(tenantId, email).orElse(null);
            boolean isNew = p == null;
            if (isNew) {
                p = new Prospect();
                p.setId(UUID.randomUUID().toString());
                p.setTenantId(tenantId);
                p.setEmail(email);
                p.setCreatedAt(OffsetDateTime.now());
            }
            p.setName(str(r.get("name"), p.getName()));
            p.setPhone(str(r.get("phone"), p.getPhone()));
            p.setSource(str(r.get("source"), p.getSource() != null ? p.getSource() : defaultSource));
            p.setSocialRef(str(r.get("socialRef"), p.getSocialRef()));
            // Never downgrade an already-consented prospect; upgrade only on a basis.
            if (consented) {
                p.setConsent(Prospect.CONSENTED);
                p.setLawfulBasis(lawfulBasis);
            } else if (p.getConsent() == null) {
                p.setConsent(Prospect.UNCONSENTED);
            }
            p.setUpdatedAt(OffsetDateTime.now());
            prospects.save(p);
            if (isNew) imported++; else updated++;
            if (Prospect.CONSENTED.equals(p.getConsent())) reachable++;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("imported", imported);
        out.put("updated", updated);
        out.put("reachable", reachable);
        out.put("heldUnconsented", (imported + updated) - reachable);
        return out;
    }

    /**
     * Capture a first-party LEAD as a reachable prospect — a social lead-form
     * entry or an inbound enquiry the operator itself received. Unlike a bulk
     * list import, a captured lead is consented by nature (the person engaged
     * you), so it lands reachable with a recorded basis. Idempotent per email.
     */
    @Transactional
    public void captureLead(String email, String name, String source, String lawfulBasis) {
        if (email == null || email.isBlank()) {
            return;
        }
        String tenantId = tenantScope.currentTenantId();
        String key = email.trim().toLowerCase();
        Prospect p = prospects.findByTenantIdAndEmail(tenantId, key).orElse(null);
        boolean isNew = p == null;
        if (isNew) {
            p = new Prospect();
            p.setId(UUID.randomUUID().toString());
            p.setTenantId(tenantId);
            p.setEmail(key);
            p.setCreatedAt(OffsetDateTime.now());
        }
        if (name != null && !name.isBlank()) p.setName(name);
        if (p.getSource() == null) p.setSource(source == null ? "lead" : source);
        p.setConsent(Prospect.CONSENTED);
        if (p.getLawfulBasis() == null) p.setLawfulBasis(lawfulBasis == null ? "inbound-lead" : lawfulBasis);
        p.setUpdatedAt(OffsetDateTime.now());
        prospects.save(p);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(Map<String, String> filters) {
        String tenantId = tenantScope.currentTenantId();
        return prospects.findByTenantId(tenantId).stream()
                .filter(p -> filters.get("source") == null || filters.get("source").equals(p.getSource()))
                .filter(p -> filters.get("consent") == null || filters.get("consent").equals(p.getConsent()))
                .map(ProspectService::toMap)
                .toList();
    }

    static Map<String, Object> toMap(Prospect p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("email", p.getEmail());
        m.put("name", p.getName());
        m.put("phone", p.getPhone());
        m.put("source", p.getSource());
        m.put("consent", p.getConsent());
        m.put("lawfulBasis", p.getLawfulBasis());
        m.put("@type", "Prospect");
        return m;
    }

    private static String str(Object o, String dflt) {
        return o == null ? dflt : String.valueOf(o);
    }
}
