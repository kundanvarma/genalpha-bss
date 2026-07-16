package com.bss.som.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * The telesales dial list reads its audience from insight — the SAME
 * segment source campaigns use, so a call list is never a guess and
 * consent is filtered AT THE SOURCE (insight only returns members who
 * consented). Fail-closed: an unreadable segment is an empty list, not
 * an unwashed one.
 */
@Component
public class InsightClient {

    private static final Logger log = LoggerFactory.getLogger(InsightClient.class);

    private final RestClient restClient;

    public InsightClient(RestClient.Builder builder, MachineTokenInterceptor tokenInterceptor,
            @Value("${bss.downstream.insight-base-url:http://localhost:8117}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).requestInterceptor(tokenInterceptor).build();
    }

    @SuppressWarnings("unchecked")
    public List<String> segmentMembers(String segment) {
        try {
            List<Map<String, Object>> members = restClient.get()
                    .uri(uri -> uri.path("/insight/v1/segmentMembers")
                            .queryParam("segment", segment).build())
                    .retrieve().body(List.class);
            return members == null ? List.of()
                    : members.stream().map(m -> String.valueOf(m.get("partyId"))).toList();
        } catch (Exception e) {
            log.warn("segment '{}' unreadable — the dial list stays empty: {}", segment, e.getMessage());
            return List.of();
        }
    }
}
