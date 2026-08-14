package com.bss.communication.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * SMS delivery seam — stands in front of the A2P provider an operator already
 * pays for (Twilio, Sinch, Vonage). Speaks Twilio's Messages wire shape to a
 * configured gateway; the recipient's number is looked up live from party.
 * Fail-open: the in-app inbox already holds the message.
 */
@Component
public class SmsForwarder {

    private static final Logger log = LoggerFactory.getLogger(SmsForwarder.class);

    private final RestClient partyClient;
    private final RestClient smsClient;
    private final MachineTokens tokens;
    private final String gatewayUrl;
    private final String from;

    public SmsForwarder(RestClient.Builder builder, MachineTokens tokens,
            @Value("${bss.downstream.party-base-url:http://localhost:8081}") String partyBaseUrl,
            @Value("${bss.downstream.sms-gateway-url:}") String gatewayUrl,
            @Value("${bss.downstream.sms-from:+100000000}") String from) {
        this.partyClient = builder.baseUrl(partyBaseUrl).build();
        this.smsClient = builder.build();
        this.tokens = tokens;
        this.gatewayUrl = gatewayUrl;
        this.from = from;
    }

    public void forward(String tenantId, String messageId, String partyId, String body) {
        if (gatewayUrl == null || gatewayUrl.isBlank() || partyId == null || body == null) {
            return; // no SMS gateway wired for this deployment — in-app only
        }
        CompletableFuture.runAsync(() -> {
            try {
                String phone = phoneOf(tenantId, partyId);
                if (phone == null) {
                    log.debug("sms skipped: party {} has no phone number", partyId);
                    return;
                }
                Map<String, Object> msg = Map.of("To", phone, "From", from, "Body", body,
                        "custom_args", Map.of("messageId", messageId, "tenant", tenantId));
                smsClient.post().uri(gatewayUrl + "/2010-04-01/Messages.json")
                        .header("Authorization", "Bearer " + tokens.tokenFor(tenantId))
                        .body(msg)
                        .retrieve().toBodilessEntity();
            } catch (Exception e) {
                log.debug("sms forward skipped: {}", e.getMessage());
            }
        });
    }

    @SuppressWarnings("unchecked")
    private String phoneOf(String tenantId, String partyId) {
        Map<String, Object> person = partyClient.get()
                .uri("/tmf-api/party/v4/individual/{id}", partyId)
                .header("Authorization", "Bearer " + tokens.tokenFor(tenantId))
                .retrieve().body(Map.class);
        if (person == null || !(person.get("contactMedium") instanceof List<?> media)) {
            return null;
        }
        for (Object m : media) {
            if (m instanceof Map<?, ?> medium
                    && ("phone".equalsIgnoreCase(String.valueOf(medium.get("mediumType")))
                        || "mobile".equalsIgnoreCase(String.valueOf(medium.get("mediumType"))))
                    && medium.get("characteristic") instanceof Map<?, ?> c) {
                Object number = c.get("phoneNumber") != null ? c.get("phoneNumber") : c.get("number");
                if (number != null) return String.valueOf(number);
            }
        }
        return null;
    }
}
