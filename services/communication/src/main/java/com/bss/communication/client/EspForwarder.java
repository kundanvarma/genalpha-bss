package com.bss.communication.client;

import com.bss.communication.repository.SuppressionRepository;
import com.bss.communication.security.TenantContext;
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
 * The ESP (Email Service Provider) delivery seam: with
 * DELIVERY_PROVIDER=esp a tenant's customer messages ALSO go out through
 * the provider the operator already pays for — SendGrid v3 wire shape
 * (POST /v3/mail/send), which Mailgun/SES-style gateways and the dev mock
 * both speak. The in-app inbox stays the source of truth; this send is
 * fire-and-forget and fail-open, so a slow or dead ESP never delays a
 * notification. The recipient's address is looked up live from party
 * management — the BSS already knows it; no second copy.
 *
 * Respecting the answer: an address the provider bounced sits on the
 * tenant's suppression list and is skipped here (custom_args carries the
 * message id so the provider's receipts can find their way back).
 */
@Component
public class EspForwarder {

    private static final Logger log = LoggerFactory.getLogger(EspForwarder.class);

    private final TenantRegistry tenants;
    private final MachineTokens tokens;
    private final SuppressionRepository suppressions;
    private final RestClient partyClient;
    private final RestClient espClient;

    public EspForwarder(TenantRegistry tenants, MachineTokens tokens,
            SuppressionRepository suppressions, RestClient.Builder builder,
            @Value("${bss.downstream.party-base-url:http://localhost:8081}") String partyBaseUrl) {
        this.tenants = tenants;
        this.tokens = tokens;
        this.suppressions = suppressions;
        this.partyClient = builder.baseUrl(partyBaseUrl).build();
        this.espClient = builder.build();
    }

    public void forward(String tenantId, String messageId, String partyId,
            String subject, String content) {
        TenantRegistry.TenantEntry tenant = tenants.byId(tenantId);
        if (tenant == null || !"esp".equalsIgnoreCase(tenant.getDeliveryProvider())
                || tenant.getEspUrl() == null || tenant.getEspUrl().isBlank()
                || subject == null || partyId == null) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            // an async thread has no request; act as the tenant so the
            // row-level policies admit the suppression lookup
            try (TenantContext ignored = TenantContext.actAs(tenantId)) {
                String email = emailOf(tenantId, partyId);
                if (email == null) {
                    log.debug("esp skipped: party {} has no email address", partyId);
                    return;
                }
                if (suppressions.existsByTenantIdAndEmail(tenantId, email)) {
                    log.info("esp suppressed: {} bounced before — in-app only", email);
                    return;
                }
                Map<String, Object> mail = Map.of(
                        "personalizations", List.of(Map.of("to", List.of(Map.of("email", email)))),
                        "from", Map.of("email", tenant.getEspFrom() == null
                                ? "no-reply@" + tenantId + ".example" : tenant.getEspFrom()),
                        "subject", subject,
                        "content", List.of(Map.of("type", "text/plain",
                                "value", content == null ? "" : content)),
                        // the receipt's way home (SendGrid echoes these back)
                        "custom_args", Map.of("messageId", messageId, "tenant", tenantId));
                espClient.post().uri(tenant.getEspUrl() + "/v3/mail/send")
                        .header("Authorization", "Bearer " + tenant.getEspApiKey())
                        .body(mail)
                        .retrieve().toBodilessEntity();
            } catch (Exception e) {
                // fail-open: the inbox already has the message
                log.debug("esp forward skipped: {}", e.getMessage());
            }
        });
    }

    @SuppressWarnings("unchecked")
    private String emailOf(String tenantId, String partyId) {
        Map<String, Object> person = partyClient.get()
                .uri("/tmf-api/party/v4/individual/{id}", partyId)
                .header("Authorization", "Bearer " + tokens.tokenFor(tenantId))
                .retrieve().body(Map.class);
        if (person == null || !(person.get("contactMedium") instanceof List<?> media)) {
            return null;
        }
        for (Object m : media) {
            if (m instanceof Map<?, ?> medium && "email".equalsIgnoreCase(String.valueOf(medium.get("mediumType")))
                    && medium.get("characteristic") instanceof Map<?, ?> c
                    && c.get("emailAddress") != null) {
                return String.valueOf(c.get("emailAddress"));
            }
        }
        return null;
    }
}
