package com.bss.insight.client;

import com.bss.insight.security.TenantRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * The analytics seam, outbound side: with ANALYTICS_PROVIDER=ga4 the
 * tenant's CONSENTED events also flow to their own GA4 property via the
 * Measurement Protocol — bring your own analytics, per tenant. 'internal'
 * (the default) keeps everything first-party. Fire-and-forget and
 * fail-open: a slow or dead analytics endpoint never slows the shop.
 */
@Component
public class AnalyticsForwarder {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsForwarder.class);

    private final TenantRegistry tenants;
    private final RestClient restClient;

    public AnalyticsForwarder(TenantRegistry tenants, RestClient.Builder builder) {
        this.tenants = tenants;
        this.restClient = builder.build();
    }

    public void forward(String tenantId, String visitorId, String type,
            String category, String offeringId) {
        TenantRegistry.TenantEntry tenant = tenants.byId(tenantId);
        if (tenant == null || !"ga4".equalsIgnoreCase(tenant.getAnalyticsProvider())
                || tenant.getAnalyticsMeasurementId() == null
                || tenant.getAnalyticsMeasurementId().isBlank()) {
            return;
        }
        Map<String, Object> params = new LinkedHashMap<>();
        if (category != null) {
            params.put("item_category", category);
        }
        if (offeringId != null) {
            params.put("item_id", offeringId);
        }
        Map<String, Object> payload = Map.of(
                "client_id", visitorId,
                "events", List.of(Map.of(
                        "name", "view".equals(type) ? "view_item" : type,
                        "params", params)));
        CompletableFuture.runAsync(() -> {
            try {
                restClient.post()
                        .uri(tenant.getAnalyticsMpUrl() + "/mp/collect?measurement_id="
                                + tenant.getAnalyticsMeasurementId()
                                + "&api_secret=" + tenant.getAnalyticsApiSecret())
                        .body(payload)
                        .retrieve().toBodilessEntity();
            } catch (Exception e) {
                log.debug("analytics forward skipped: {}", e.getMessage());
            }
        });
    }
}
