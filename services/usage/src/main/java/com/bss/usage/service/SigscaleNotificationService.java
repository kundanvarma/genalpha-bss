package com.bss.usage.service;

import com.bss.usage.client.OcsSettings;
import com.bss.usage.client.SigscaleOcsClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SigScale OCS → BSS: the "running low" line, owned by the OCS.
 *
 * SigScale's TMF654 balance hub posts an {@code AccumulatedBalanceCreationNotification}
 * every time a product's data balance moves while it sits under the OCS's
 * own threshold ({@code threshold_bytes}; the hub query filters on the same
 * number). This service subscribes each SigScale-bound tenant's hub at boot
 * (idempotent — an existing subscription with our callback is reused) and
 * translates the events into the tenant-stamped {@code UsageThresholdBreachedEvent}
 * the growth engine already listens for. One notification per low episode:
 * the product is remembered as "told" until its balance climbs back over the
 * line (a top-up, a new month) — then it may warn again.
 */
@Service
public class SigscaleNotificationService {

    private static final Logger log = LoggerFactory.getLogger(SigscaleNotificationService.class);

    private final SigscaleOcsClient sigscale;
    private final OcsSettings settings;
    private final UsageService usage;
    private final String notifyBaseUrl;
    private final long thresholdBytes;
    private final Set<String> told = ConcurrentHashMap.newKeySet();

    public SigscaleNotificationService(SigscaleOcsClient sigscale, OcsSettings settings, UsageService usage,
            @Value("${bss.ocs.notify-base-url:http://localhost:8080}") String notifyBaseUrl,
            @Value("${bss.ocs.sigscale-threshold-bytes:2000000000}") long thresholdBytes) {
        this.sigscale = sigscale;
        this.settings = settings;
        this.usage = usage;
        this.notifyBaseUrl = notifyBaseUrl == null ? "" : notifyBaseUrl.replaceAll("/+$", "");
        this.thresholdBytes = thresholdBytes;
    }

    public String callbackFor(String tenantId) {
        return notifyBaseUrl + "/internal/ocs/sigscale/" + tenantId;
    }

    public String hubQuery() {
        return "totalBalance.units=octets&totalBalance.amount.lt=" + thresholdBytes;
    }

    /* ----------------------------------------------------------- inbound */

    /** One hub delivery (possibly several events). Returns what was relayed. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> accept(String tenantId, Map<String, Object> body) {
        List<Map<String, Object>> relayed = new java.util.ArrayList<>();
        Object events = body == null ? null : body.get("event");
        if (!(events instanceof List<?> list)) {
            return Map.of("status", "ignored", "reason", "no event array");
        }
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> raw)) {
                continue;
            }
            Map<String, Object> event = (Map<String, Object>) raw;
            String productId = event.get("id") == null ? null : String.valueOf(event.get("id"));
            if (productId == null) {
                continue;
            }
            double remainingBytes = totalOctets(event);
            if (remainingBytes >= thresholdBytes) {
                told.remove(productId); // back over the line: re-arm
                continue;
            }
            if (!told.add(productId)) {
                continue; // this low episode was already relayed
            }
            Map<String, Object> product = sigscale.product(tenantId, productId);
            if (product == null || !tenantId.equals(SigscaleOcsClient.characteristic(product, "bssTenantId"))) {
                told.remove(productId);
                log.warn("SigScale OCS: threshold event for product {} that is not tenant {}'s — ignored", productId, tenantId);
                continue;
            }
            // an anonymous door: the tenant comes from the hub subscription, so
            // bind it before any RLS-scoped read (the top-up log) happens
            Map<String, Object> n;
            try (com.bss.usage.security.TenantContext ignored = com.bss.usage.security.TenantContext.actAs(tenantId)) {
                n = translate(tenantId, product, remainingBytes);
            }
            usage.notifyUsageThreshold(n);
            relayed.add(n);
            log.info("SigScale OCS: {} GB left on {} (party {}) — running-low relayed for tenant {}",
                    n.get("remainingGB"), productId, n.get("partyId"), tenantId);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "accepted");
        out.put("relayed", relayed);
        return out;
    }

    Map<String, Object> translate(String tenantId, Map<String, Object> product, double remainingBytes) {
        Map<String, Object> proj = sigscale.project(product);
        @SuppressWarnings("unchecked")
        Map<String, Object> bucket = ((List<Map<String, Object>>) proj.get("buckets")).get(0);
        double remainingGb = SigscaleOcsClient.round(remainingBytes / SigscaleOcsClient.GB);
        double totalGb = ((Number) bucket.get("totalGB")).doubleValue();
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("tenantId", tenantId);
        n.put("partyId", proj.get("partyId"));
        n.put("serviceId", proj.get("serviceId"));
        n.put("ratePlanId", proj.get("ratePlanId"));
        n.put("bucketName", bucket.get("name"));
        n.put("totalGB", totalGb);
        n.put("usedGB", SigscaleOcsClient.round(Math.max(0, totalGb - remainingGb)));
        n.put("remainingGB", remainingGb);
        if (totalGb > 0) {
            n.put("percentUsed", (int) Math.round(Math.max(0, totalGb - remainingGb) / totalGb * 100));
            n.put("threshold", SigscaleOcsClient.round(1 - (thresholdBytes / SigscaleOcsClient.GB) / totalGb));
        }
        n.put("units", "GB");
        n.put("source", "sigscale");
        return n;
    }

    static double totalOctets(Map<String, Object> event) {
        Object tb = event.get("totalBalance");
        if (tb instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m && "octets".equals(m.get("units"))) {
                    return SigscaleOcsClient.octets(m.get("amount"));
                }
            }
        }
        return Double.MAX_VALUE; // not a data balance: never "low"
    }

    /* --------------------------------------------------------- the hub */

    @EventListener(ApplicationReadyEvent.class)
    public void subscribeHubs() {
        List<OcsSettings.Binding> bound = settings.tenantsOn("sigscale");
        if (bound.isEmpty()) {
            return;
        }
        Thread t = new Thread(() -> {
            for (int attempt = 1; attempt <= 30; attempt++) {
                boolean allDone = true;
                for (OcsSettings.Binding b : bound) {
                    if (!ensureHub(b.tenantId())) {
                        allDone = false;
                    }
                }
                if (allDone) {
                    return;
                }
                try {
                    Thread.sleep(10_000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            log.warn("SigScale OCS: balance hub not subscribed for every tenant after 5 minutes — running-low events will not arrive");
        }, "sigscale-hub-subscribe");
        t.setDaemon(true);
        t.start();
    }

    /** Subscribe this tenant's OCS balance hub to our callback; true when in place. */
    @SuppressWarnings("unchecked")
    public boolean ensureHub(String tenantId) {
        RestClient c = sigscale.client(tenantId);
        if (c == null) {
            return true;
        }
        String callback = callbackFor(tenantId);
        try {
            List<Map<String, Object>> hubs = c.get().uri(SigscaleOcsClient.BALANCE + "/hub").retrieve().body(List.class);
            if (hubs != null) {
                for (Map<String, Object> h : hubs) {
                    if (callback.equals(h.get("callback")) && hubQuery().equals(h.get("query"))) {
                        return true;
                    }
                    if (callback.equals(h.get("callback"))) {
                        // ours, but an older threshold: replace it
                        c.delete().uri(SigscaleOcsClient.BALANCE + "/hub/{id}", h.get("id")).retrieve().toBodilessEntity();
                    }
                }
            }
            c.post().uri(SigscaleOcsClient.BALANCE + "/hub").header("Content-Type", "application/json")
                    .body(Map.of("callback", callback, "query", hubQuery()))
                    .retrieve().toBodilessEntity();
            log.info("SigScale OCS: balance hub subscribed for tenant {} → {} ({})", tenantId, callback, hubQuery());
            return true;
        } catch (RuntimeException e) {
            log.info("SigScale OCS: hub subscription for tenant {} not yet possible ({}) — will retry", tenantId, e.getMessage());
            return false;
        }
    }
}
