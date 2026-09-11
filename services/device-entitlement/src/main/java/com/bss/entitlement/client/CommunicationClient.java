package com.bss.entitlement.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Server-initiated entitlement refresh (TS.43 §2.6) rides the communication
 * component: a push to the token the phone registered, else an SMS to the
 * line — both stamped {@code category: transactional} (a service notice,
 * never marketing). The wire format per transport (FCM/APNS payload,
 * application-port SMS with UDH) is the forwarder's; the payload is the
 * spec's {@code {"app": [...], "timestamp": "..."}}.
 */
@Component
public class CommunicationClient {

    private static final Logger log = LoggerFactory.getLogger(CommunicationClient.class);

    private final RestClient restClient;
    private final boolean enabled;

    public CommunicationClient(RestClient.Builder builder, MachineTokenInterceptor tokenInterceptor,
            @Value("${bss.downstream.communication-base-url:}") String baseUrl) {
        this.enabled = baseUrl != null && !baseUrl.isBlank();
        this.restClient = enabled ? builder.baseUrl(baseUrl).requestInterceptor(tokenInterceptor).build() : null;
    }

    public boolean enabled() {
        return enabled;
    }

    /** Send the refresh notice; returns the communication message id, or null when nothing was sent. */
    public String notifyRefresh(String partyId, String channel, String payloadJson, List<String> apps) {
        if (!enabled || partyId == null) {
            return null;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("messageType", channel);
        body.put("subject", "Entitlement refresh");
        body.put("content", "push".equals(channel) ? payloadJson
                : "Your phone will refresh its service settings (" + String.join(", ", apps) + ").");
        body.put("characteristic", List.of(Map.of("name", "category", "value", "transactional"),
                Map.of("name", "ts43.payload", "value", payloadJson)));
        body.put("relatedParty", List.of(Map.of("id", partyId, "role", "customer")));
        try {
            Map<?, ?> reply = restClient.post().uri("/tmf-api/communicationManagement/v4/communicationMessage")
                    .header("Content-Type", "application/json").body(body).retrieve().body(Map.class);
            return reply == null || reply.get("id") == null ? null : String.valueOf(reply.get("id"));
        } catch (RuntimeException e) {
            log.warn("entitlement refresh over {} not sent to party {} ({})", channel, partyId, e.getMessage());
            return null;
        }
    }
}
