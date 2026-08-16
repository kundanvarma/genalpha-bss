package com.bss.campaign.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Map;

@Component
public class RestCommunicationClient implements CommunicationClient {

    private final RestClient restClient;

    public RestCommunicationClient(RestClient.Builder builder, MachineTokenInterceptor tokenInterceptor,
            @Value("${bss.downstream.communication-base-url:http://localhost:8095}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).requestInterceptor(tokenInterceptor).build();
    }

    @Override
    public void send(String partyId, String subject, String content, Map<String, Object> context) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("subject", subject);
        body.put("content", content);
        body.put("messageType", "inApp");
        if (context != null && !context.isEmpty()) {
            body.put("context", context);
            if (context.get("source") != null) body.put("source", context.get("source"));
        }
        body.put("relatedParty", List.of(Map.of("id", partyId, "role", "customer")));
        try {
            restClient.post().uri("/tmf-api/communicationManagement/v4/communicationMessage")
                    .header("Content-Type", "application/json")
                    .body(body)
                    .retrieve().toBodilessEntity();
        } catch (RestClientException e) {
            throw new IllegalStateException("communication rejected the campaign message", e);
        }
    }

    @Override
    public void sendTemplated(String partyId, String templateRef, String locale, String channel,
            Map<String, Object> context) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("templateRef", templateRef);
        if (locale != null) body.put("locale", locale);
        if (channel != null) body.put("messageType", channel);
        if (context != null && !context.isEmpty()) {
            body.put("context", context);
            if (context.get("source") != null) body.put("source", context.get("source"));
        }
        body.put("relatedParty", List.of(Map.of("id", partyId, "role", "customer")));
        try {
            restClient.post().uri("/tmf-api/communicationManagement/v4/communicationMessage")
                    .header("Content-Type", "application/json")
                    .body(body)
                    .retrieve().toBodilessEntity();
        } catch (RestClientException e) {
            throw new IllegalStateException("communication rejected the templated campaign message", e);
        }
    }
}
