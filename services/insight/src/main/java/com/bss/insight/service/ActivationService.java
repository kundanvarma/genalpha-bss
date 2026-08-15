package com.bss.insight.service;

import com.bss.insight.entity.PartyTrait;
import com.bss.insight.repository.PartyTraitRepository;
import com.bss.insight.security.TenantContext;
import com.bss.insight.security.TenantScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Activate an audience OUT to an ad/social platform — ASYNC. The request creates
 * a job and returns immediately; the (potentially huge) hashed export runs in
 * the background so nothing blocks. Two modes: seed (lookalike source) and
 * suppress (paid-spend exclusion). Emails resolve with no per-row fan-out — a
 * prospect carries its own; a customer's is read from the denormalised trait
 * store in one query. Hashing + batching live in {@link SocialAudienceClient}.
 */
@Service
public class ActivationService {

    private static final Logger log = LoggerFactory.getLogger(ActivationService.class);

    private final AudienceService audiences;
    private final Map<String, com.bss.insight.client.AdDestination> destinations;
    private final PartyTraitRepository traits;
    private final ActivationJobService jobs;
    private final com.bss.insight.repository.EmailSuppressionRepository suppressions;
    private final TenantScope tenantScope;

    public ActivationService(AudienceService audiences,
            List<com.bss.insight.client.AdDestination> destinations,
            PartyTraitRepository traits, ActivationJobService jobs,
            com.bss.insight.repository.EmailSuppressionRepository suppressions, TenantScope tenantScope) {
        this.audiences = audiences;
        this.destinations = new LinkedHashMap<>();
        for (com.bss.insight.client.AdDestination d : destinations) this.destinations.put(d.name(), d);
        this.traits = traits;
        this.jobs = jobs;
        this.suppressions = suppressions;
        this.tenantScope = tenantScope;
    }

    /** Queue the export and return the job — the push runs in the background. */
    public Map<String, Object> activate(String audienceId, Map<String, Object> body) {
        String externalAudienceId = body.get("externalAudienceId") == null
                ? null : String.valueOf(body.get("externalAudienceId"));
        String mode = "suppress".equals(body.get("mode")) ? "suppress" : "seed";
        String destination = body.get("destination") == null ? "meta" : String.valueOf(body.get("destination"));
        if (externalAudienceId == null || externalAudienceId.isBlank()) {
            throw new IllegalArgumentException("externalAudienceId (the platform Custom Audience id) is required");
        }
        if (!destinations.containsKey(destination)) {
            throw new IllegalArgumentException("unknown destination '" + destination + "' — known: " + destinations.keySet());
        }
        String jobId = jobs.createQueued(audienceId, externalAudienceId, mode, destination);
        String tenantId = tenantScope.currentTenantId();
        CompletableFuture.runAsync(() -> {
            try (TenantContext ignored = TenantContext.actAs(tenantId)) {
                runJob(jobId, audienceId, externalAudienceId, destination);
            }
        });
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("jobId", jobId);
        out.put("mode", mode);
        out.put("destination", destination);
        out.put("status", "queued");
        out.put("enabled", destinations.get(destination).enabled());
        return out;
    }

    public Map<String, Object> jobStatus(String jobId) {
        return jobs.status(jobId);
    }

    /** The activatable destinations (platform keys) and whether each is configured. */
    public Map<String, Object> availableDestinations() {
        Map<String, Object> out = new LinkedHashMap<>();
        destinations.forEach((k, v) -> out.put(k, v.enabled()));
        return out;
    }

    /** Background worker: resolve the audience, hash+push, record the outcome.
     * Each state transition commits on its own so a poller sees running->done. */
    private void runJob(String jobId, String audienceId, String externalAudienceId, String destination) {
        jobs.markRunning(jobId);
        try {
            List<Map<String, Object>> members = audiences.members(audienceId, null);
            Map<String, String> emailByParty = null;
            List<String> emails = new ArrayList<>();
            for (Map<String, Object> m : members) {
                Object email = m.get("email");
                if (email != null && !String.valueOf(email).isBlank()) {
                    emails.add(String.valueOf(email));
                    continue;
                }
                Object partyId = m.get("partyId");
                if (partyId != null) {
                    if (emailByParty == null) {
                        emailByParty = new LinkedHashMap<>();
                        for (PartyTrait t : traits.findByTenantIdAndTraitKey(tenantScope.currentTenantId(), "email")) {
                            emailByParty.putIfAbsent(t.getPartyId(), t.getTraitValue());
                        }
                    }
                    String e = emailByParty.get(String.valueOf(partyId));
                    if (e != null) emails.add(e);
                }
            }
            // DNC: never export a suppressed (bounced/complained/unsubscribed) address.
            // Projected locally from communication's EmailSuppressedEvent — a fast
            // local lookup, no cross-service call on the export path.
            java.util.Set<String> dnc = suppressions.findByTenantId(tenantScope.currentTenantId()).stream()
                    .map(s -> s.getEmail() == null ? "" : s.getEmail().trim().toLowerCase())
                    .collect(java.util.stream.Collectors.toSet());
            int beforeDnc = emails.size();
            if (!dnc.isEmpty()) {
                emails.removeIf(e -> dnc.contains(e.trim().toLowerCase()));
            }
            // Hash once (SHA-256), then format per the chosen platform's wire shape.
            List<String> hashes = new ArrayList<>(emails.size());
            for (String e : emails) hashes.add(com.bss.insight.client.Hashing.sha256(e));
            int pushed = destinations.get(destination).push(externalAudienceId, hashes);
            jobs.complete(jobId, members.size(), pushed, members.size() - emails.size());
            if (beforeDnc != emails.size()) {
                log.info("activation {}: DNC-filtered {} suppressed address(es) before export", jobId, beforeDnc - emails.size());
            }
        } catch (Exception e) {
            log.warn("activation job {} failed: {}", jobId, e.getMessage());
            jobs.fail(jobId, e.getMessage());
        }
    }
}
