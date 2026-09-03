package com.bss.communication.client;

import com.bss.communication.security.TenantRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * WhatsApp delivery seam — the channel a Guyanese customer actually answers
 * (GPL, GWI, the banks and every operator run a WhatsApp line). Speaks the
 * Meta WhatsApp Business Cloud API wire shape (POST /{version}/{phoneId}/messages,
 * text message) to the TENANT's own Business account: URL, phone-number id
 * and the env var naming the token all come from the tenant registry, so
 * two operators never share a sender. Fail-open: the inbox holds the message.
 */
@Component
public class WhatsAppForwarder {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppForwarder.class);

    private final RestClient partyClient;
    private final RestClient client;
    private final MachineTokens tokens;
    private final TenantRegistry tenants;

    public WhatsAppForwarder(RestClient.Builder builder, MachineTokens tokens, TenantRegistry tenants,
            @Value("${bss.downstream.party-base-url:http://localhost:8081}") String partyBaseUrl) {
        this.partyClient = builder.baseUrl(partyBaseUrl).build();
        this.client = builder.build();
        this.tokens = tokens;
        this.tenants = tenants;
    }

    public void forward(String tenantId, String messageId, String partyId, String body) {
        TenantRegistry.TenantEntry te = tenants.byId(tenantId);
        if (te == null || te.getWhatsappUrl() == null || te.getWhatsappUrl().isBlank()
                || te.getWhatsappPhoneId() == null || partyId == null || body == null) {
            return; // this operator has no WhatsApp Business line wired — in-app only
        }
        CompletableFuture.runAsync(() -> {
            try {
                String phone = phoneOf(tenantId, partyId);
                if (phone == null) {
                    log.debug("whatsapp skipped: party {} has no phone number", partyId);
                    return;
                }
                String token = te.getWhatsappTokenRef() == null ? null : System.getenv(te.getWhatsappTokenRef());
                Map<String, Object> msg = Map.of(
                        "messaging_product", "whatsapp",
                        "recipient_type", "individual",
                        "to", phone.replace("+", "").replace(" ", ""),
                        "type", "text",
                        "text", Map.of("preview_url", false, "body", body),
                        "biz_opaque_callback_data", messageId);
                var req = client.post().uri(te.getWhatsappUrl().replaceAll("/+$", "") + "/v20.0/{phoneId}/messages",
                                te.getWhatsappPhoneId())
                        .header("Content-Type", "application/json")
                        .header("X-Tenant-Id", tenantId);
                if (token != null && !token.isBlank()) {
                    req = req.header("Authorization", "Bearer " + token);
                }
                req.body(msg).retrieve().toBodilessEntity();
                log.info("whatsapp message {} sent for tenant {}", messageId, tenantId);
            } catch (Exception e) {
                log.debug("whatsapp forward skipped: {}", e.getMessage());
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
                        || "mobile".equalsIgnoreCase(String.valueOf(medium.get("mediumType")))
                        || "whatsapp".equalsIgnoreCase(String.valueOf(medium.get("mediumType"))))
                    && medium.get("characteristic") instanceof Map<?, ?> c) {
                Object number = c.get("phoneNumber") != null ? c.get("phoneNumber") : c.get("number");
                if (number != null && !String.valueOf(number).isBlank()) {
                    return String.valueOf(number);
                }
            }
        }
        return null;
    }
}
