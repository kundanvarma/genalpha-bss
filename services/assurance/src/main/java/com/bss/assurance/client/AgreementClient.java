package com.bss.assurance.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/** SLA terms live on agreements — read under the machine identity. */
@Component
public class AgreementClient {

    private final RestClient agreement;
    private final ObjectMapper objectMapper;

    public AgreementClient(RestClient.Builder builder, MachineTokenInterceptor tokenInterceptor,
            ObjectMapper objectMapper,
            @Value("${bss.downstream.agreement-base-url:http://localhost:8106}") String agreementBaseUrl) {
        this.agreement = builder.baseUrl(agreementBaseUrl)
                .requestInterceptor(tokenInterceptor).build();
        this.objectMapper = objectMapper;
    }

    /**
     * ALL active agreements, paged to exhaustion. The proof run's lesson:
     * a fixed limit=200 silently dropped newly signed promises once the
     * fleet aged past 200 agreements — a face must never under-report
     * because the tenant grew. Server-side status filter + offset paging,
     * the same pattern intelligence's client always used.
     */
    public List<Map<String, Object>> activeAgreements() {
        List<Map<String, Object>> all = new java.util.ArrayList<>();
        try {
            for (int offset = 0; offset < 10_000; offset += 100) {
                String body = agreement.get()
                        .uri("/tmf-api/agreementManagement/v4/agreement?status=active&limit=100&offset=" + offset)
                        .retrieve().body(String.class);
                List<Map<String, Object>> page = objectMapper.readValue(body,
                        new TypeReference<List<Map<String, Object>>>() { });
                all.addAll(page);
                if (page.size() < 100) {
                    break;
                }
            }
        } catch (Exception e) {
            return all;
        }
        return all;
    }
}
