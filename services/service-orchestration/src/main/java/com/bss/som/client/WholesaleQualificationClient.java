package com.bss.som.client;

import com.bss.som.security.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Map;

/**
 * "Which fibre owners can serve this address?" — the wholesale shortlist, from the
 * qualification service (TMF645 queryAccessOptions). Anonymous shop-window
 * functionality, so only the tenant header is carried. Fail-open: an outage means
 * no wholesale options were found, and provisioning falls back to our own network.
 */
@Component
public class WholesaleQualificationClient {

    private static final Logger log = LoggerFactory.getLogger(WholesaleQualificationClient.class);
    private static final String QUERY =
            "/tmf-api/serviceQualificationManagement/v4/queryAccessOptions";

    private final RestClient restClient;

    public WholesaleQualificationClient(RestClient.Builder builder,
            @Value("${bss.downstream.qualification-base-url:http://localhost:8080}") String baseUrl) {
        this.restClient = builder.clone().baseUrl(baseUrl).build();
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> accessOptions(String postCode, String technology) {
        if (postCode == null || postCode.isBlank()) {
            return List.of();
        }
        try {
            Map<String, Object> body = restClient.post()
                    .uri(QUERY)
                    .header("X-Tenant-Id", TenantContext.current())
                    .header("Content-Type", "application/json")
                    .body(Map.of("searchCriteria", Map.of(
                            "place", Map.of("postCode", postCode), "technology", technology)))
                    .retrieve()
                    .body(Map.class);
            Object options = body == null ? null : body.get("accessOption");
            return options instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
        } catch (RestClientException e) {
            log.warn("qualification unreachable, no wholesale options (fail-open): {}", e.getMessage());
            return List.of();
        }
    }
}
