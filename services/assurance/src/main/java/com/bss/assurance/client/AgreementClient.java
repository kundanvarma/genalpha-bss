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

    public List<Map<String, Object>> activeAgreements() {
        try {
            String body = agreement.get()
                    .uri("/tmf-api/agreementManagement/v4/agreement?limit=200")
                    .retrieve().body(String.class);
            return objectMapper.readValue(body, new TypeReference<List<Map<String, Object>>>() { });
        } catch (Exception e) {
            return List.of();
        }
    }
}
