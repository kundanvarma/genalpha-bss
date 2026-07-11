package com.bss.ordering.client;

import com.bss.ordering.exception.DownstreamException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Map;

@Component
@ConditionalOnProperty(name = "bss.agreement.enabled", havingValue = "true", matchIfMissing = true)
public class RestAgreementClient implements AgreementClient {

    private final RestClient restClient;

    public RestAgreementClient(RestClient.Builder builder, MachineTokenInterceptor tokenInterceptor,
            @Value("${bss.downstream.agreement-base-url:http://localhost:8098}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).requestInterceptor(tokenInterceptor).build();
    }

    @Override
    public void activate(String name, String ownerPartyId, List<Map<String, Object>> items,
            int commitmentMonths) {
        try {
            restClient.post().uri("/tmf-api/agreementManagement/v4/agreement")
                    .header("Content-Type", "application/json")
                    .body(Map.of(
                            "name", name,
                            "agreementType", "commercial",
                            "status", "active",
                            "commitmentMonths", commitmentMonths,
                            "engagedParty", List.of(Map.of("id", ownerPartyId, "role", "customer")),
                            "agreementItem", items))
                    .retrieve().toBodilessEntity();
        } catch (RestClientException e) {
            throw new DownstreamException("agreement service rejected the commitment", e);
        }
    }
}
