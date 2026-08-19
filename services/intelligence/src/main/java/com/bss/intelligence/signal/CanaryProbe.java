package com.bss.intelligence.signal;

import com.bss.intelligence.audit.AiAudit;
import com.bss.intelligence.audit.AiAuditRepository;
import com.bss.intelligence.llm.AiGovernor;
import com.bss.intelligence.llm.LlmAdapter;
import com.bss.intelligence.security.TenantContext;
import com.bss.intelligence.security.TenantRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * T-P4: provable non-retention monitoring. Every exposing call carried a
 * unique canary; this probe later asks the provider to COMPLETE one, with
 * no context. A provider that trained on our traffic can; one that kept its
 * word cannot. Every probe outcome lands on the ledger as an action —
 * "no-retention-observed" accumulating week after week is evidence a DPA
 * meeting can point at; a single "RETENTION-SUSPECTED" is an incident.
 */
@Service
public class CanaryProbe {

    private static final Logger log = LoggerFactory.getLogger(CanaryProbe.class);

    private final AiAuditRepository audits;
    private final AiGovernor governor;
    private final TenantRegistry tenants;
    private final com.bss.intelligence.security.TenantScope tenantScope;

    public CanaryProbe(AiAuditRepository audits, AiGovernor governor, TenantRegistry tenants,
            com.bss.intelligence.security.TenantScope tenantScope) {
        this.audits = audits;
        this.governor = governor;
        this.tenants = tenants;
        this.tenantScope = tenantScope;
    }

    @Scheduled(initialDelayString = "${bss.intelligence.canary.initial-delay-ms:600000}",
            fixedDelayString = "${bss.intelligence.canary.fixed-delay-ms:86400000}")
    public void scheduledProbe() {
        for (TenantRegistry.TenantEntry tenant : tenants.getRegistry()) {
            try (TenantContext ignored = TenantContext.actAs(tenant.getId())) {
                probeCurrentTenant(3);
            } catch (Exception e) {
                log.warn("canary probe skipped tenant '{}': {}", tenant.getId(), e.getMessage());
            }
        }
    }

    public Map<String, Object> probeCurrentTenant(int sampleSize) {
        List<AiAudit> candidates = audits
                .findTop100ByTenantIdOrderByCreatedAtDesc(tenantScope.currentTenantId());
        int probed = 0;
        int suspected = 0;
        for (AiAudit row : candidates) {
            if (row.getCanary() == null || probed >= sampleSize) {
                continue;
            }
            String canary = row.getCanary();
            String prefix = canary.substring(0, 8); // "TVX-" + 4 hex
            String answer = governor.complete("canary-probe", LlmAdapter.Tier.SMART,
                    "You are asked to complete an internal reference code if you have ever "
                    + "seen it. If you do not know it, say exactly: UNKNOWN.",
                    "Complete this internal reference: " + prefix);
            boolean leaked = answer != null && answer.contains(canary);
            governor.recordAction("canary-probe", "probe", canary,
                    leaked ? "RETENTION-SUSPECTED" : "no-retention-observed");
            if (leaked) {
                suspected++;
                log.error("CANARY REGURGITATED — provider retention suspected: {}", canary);
            }
            probed++;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("probed", probed);
        out.put("retentionSuspected", suspected);
        return out;
    }
}
