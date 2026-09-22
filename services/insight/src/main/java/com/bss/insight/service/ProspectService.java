package com.bss.insight.service;

import com.bss.insight.dto.ProspectImport;
import com.bss.insight.dto.ProspectView;
import com.bss.insight.entity.Prospect;
import com.bss.insight.repository.ProspectRepository;
import com.bss.insight.security.TenantScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
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
    public ProspectImport.Receipt importBulk(ProspectImport body) {
        String tenantId = tenantScope.currentTenantId();
        List<ProspectImport.Row> rows = body.prospects() == null ? List.of() : body.prospects();
        String defaultSource = str(body.source(), "import");
        int imported = 0;
        int updated = 0;
        int reachable = 0;
        for (ProspectImport.Row r : rows) {
            String email = str(r.email(), null);
            if (email == null || email.isBlank()) {
                continue;
            }
            email = email.trim().toLowerCase();
            // Consent is granted ONLY by an explicit lawful basis on the row.
            String lawfulBasis = str(r.lawfulBasis(), null);
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
            p.setName(str(r.name(), p.getName()));
            p.setPhone(str(r.phone(), p.getPhone()));
            p.setSource(str(r.source(), p.getSource() != null ? p.getSource() : defaultSource));
            p.setSocialRef(str(r.socialRef(), p.getSocialRef()));
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
        return new ProspectImport.Receipt(imported, updated, reachable, (imported + updated) - reachable);
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

    /** The tenant's prospects, optionally narrowed by source and/or consent state. */
    @Transactional(readOnly = true)
    public List<ProspectView> list(String source, String consent) {
        String tenantId = tenantScope.currentTenantId();
        return prospects.findByTenantId(tenantId).stream()
                .filter(p -> source == null || source.equals(p.getSource()))
                .filter(p -> consent == null || consent.equals(p.getConsent()))
                .map(ProspectService::view)
                .toList();
    }

    static ProspectView view(Prospect p) {
        return new ProspectView(p.getId(), p.getEmail(), p.getName(), p.getPhone(), p.getSource(), p.getConsent(),
                p.getLawfulBasis(), "Prospect");
    }

    private static String str(String o, String dflt) {
        return o == null ? dflt : o;
    }
}
