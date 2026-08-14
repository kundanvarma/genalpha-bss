package com.bss.communication.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Mobile push seam — a stub in front of the push provider (FCM/APNs/OneSignal)
 * an operator would bring. If a gateway URL is configured it forwards a simple
 * {partyId, title, body} envelope; otherwise it logs and returns. Fail-open:
 * the in-app inbox is always the record.
 */
@Component
public class PushForwarder {

    private static final Logger log = LoggerFactory.getLogger(PushForwarder.class);

    private final RestClient pushClient;
    private final String gatewayUrl;

    public PushForwarder(RestClient.Builder builder,
            @Value("${bss.downstream.push-gateway-url:}") String gatewayUrl) {
        this.pushClient = builder.build();
        this.gatewayUrl = gatewayUrl;
    }

    public void forward(String tenantId, String messageId, String partyId, String title, String body) {
        if (partyId == null || title == null) {
            return;
        }
        if (gatewayUrl == null || gatewayUrl.isBlank()) {
            log.debug("push (no gateway wired): would notify party {} — '{}'", partyId, title);
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                pushClient.post().uri(gatewayUrl + "/push")
                        .body(Map.of("tenant", tenantId, "partyId", partyId,
                                "title", title, "body", body == null ? "" : body, "messageId", messageId))
                        .retrieve().toBodilessEntity();
            } catch (Exception e) {
                log.debug("push forward skipped: {}", e.getMessage());
            }
        });
    }
}
