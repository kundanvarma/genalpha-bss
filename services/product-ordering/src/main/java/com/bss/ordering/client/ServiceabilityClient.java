package com.bss.ordering.client;

import com.bss.ordering.security.TenantScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The order-time serviceability gate: the SAME TMF679 check the storefront
 * runs at checkout, finally enforced where every channel converges. The
 * check endpoint is anonymous shop-window functionality, so no machine
 * identity is needed — only the tenant header, the same way the gateway
 * carries it for guests. Fail OPEN: a qualification outage must not block
 * commerce; the storefront's own check remains the shopper's first gate.
 */
@Component
public class ServiceabilityClient {

    private static final Logger log = LoggerFactory.getLogger(ServiceabilityClient.class);
    private static final String CHECK =
            "/tmf-api/productOfferingQualification/v4/checkProductOfferingQualification";

    private final RestClient restClient;
    private final TenantScope tenantScope;

    public ServiceabilityClient(RestClient.Builder builder, TenantScope tenantScope,
            @Value("${bss.downstream.qualification-base-url}") String baseUrl) {
        this.restClient = builder.clone().baseUrl(baseUrl).build();
        this.tenantScope = tenantScope;
    }

    /**
     * Qualify each (offering, place) pair; returns the unavailability labels
     * of every UNQUALIFIED item — empty means the order may proceed.
     */
    @SuppressWarnings("unchecked")
    public List<String> unqualifiedReasons(List<Map<String, Object>> qualificationItems) {
        try {
            Map<String, Object> body = restClient.post()
                    .uri(CHECK)
                    .header("X-Tenant-Id", tenantScope.currentTenantId())
                    .header("Content-Type", "application/json")
                    .body(Map.of("productOfferingQualificationItem", qualificationItems))
                    .retrieve()
                    .body(Map.class);
            List<String> reasons = new ArrayList<>();
            Object items = body == null ? null : body.get("productOfferingQualificationItem");
            if (items instanceof List<?> list) {
                for (Object entry : list) {
                    if (entry instanceof Map<?, ?> item
                            && "unqualified".equals(item.get("qualificationItemResult"))
                            && item.get("eligibilityUnavailabilityReason") instanceof List<?> rs
                            && !rs.isEmpty() && rs.get(0) instanceof Map<?, ?> reason) {
                        reasons.add(String.valueOf(reason.get("label")));
                    }
                }
            }
            return reasons;
        } catch (RestClientException e) {
            log.warn("qualification service unreachable, allowing order (fail-open): {}",
                    e.getMessage());
            return List.of();
        }
    }
}
