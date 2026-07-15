package com.bss.campaign.client;

import com.bss.campaign.security.TenantRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;

/**
 * AUDIENCE ACTIVATION: push a segment to the tenant's own social
 * platform as a Custom Audience (Meta Marketing API wire shape) so the
 * growth team can retarget it — emails leave ONLY as SHA-256 hashes,
 * exactly the schema Meta ingests. The addresses come live from party
 * management (machine identity); no second copy of PII is kept here.
 */
@Component
public class SocialClient {

    private static final Logger log = LoggerFactory.getLogger(SocialClient.class);

    private final TenantRegistry tenants;
    private final RestClient partyClient;
    private final RestClient socialClient;

    public SocialClient(TenantRegistry tenants, RestClient.Builder builder,
            MachineTokenInterceptor tokenInterceptor,
            @Value("${bss.downstream.party-base-url:http://localhost:8081}") String partyBaseUrl) {
        this.tenants = tenants;
        this.partyClient = builder.baseUrl(partyBaseUrl).requestInterceptor(tokenInterceptor).build();
        this.socialClient = builder.build();
    }

    public boolean configured(String tenantId) {
        TenantRegistry.TenantEntry tenant = tenants.byId(tenantId);
        return tenant != null && tenant.getSocialApiUrl() != null
                && !tenant.getSocialApiUrl().isBlank();
    }

    /** @return how many hashed members the platform accepted. */
    public int pushAudience(String tenantId, String audienceId, List<String> emails) {
        TenantRegistry.TenantEntry tenant = tenants.byId(tenantId);
        List<List<String>> data = emails.stream()
                .map(e -> List.of(sha256(e.trim().toLowerCase())))
                .toList();
        Map<String, Object> response = socialClient.post()
                .uri(tenant.getSocialApiUrl() + "/v1/" + audienceId + "/users")
                .header("Authorization", "Bearer " + tenant.getSocialAccessToken())
                .body(Map.of("schema", List.of("EMAIL_SHA256"), "data", data))
                .retrieve().body(Map.class);
        return response != null && response.get("num_received") instanceof Number n
                ? n.intValue() : 0;
    }

    /** The party's email, live from party management; null when they have none. */
    @SuppressWarnings("unchecked")
    public String emailOf(String partyId) {
        try {
            Map<String, Object> person = partyClient.get()
                    .uri("/tmf-api/party/v4/individual/{id}", partyId)
                    .retrieve().body(Map.class);
            if (person == null || !(person.get("contactMedium") instanceof List<?> media)) {
                return null;
            }
            for (Object m : media) {
                if (m instanceof Map<?, ?> medium
                        && "email".equalsIgnoreCase(String.valueOf(medium.get("mediumType")))
                        && medium.get("characteristic") instanceof Map<?, ?> c
                        && c.get("emailAddress") != null) {
                    return String.valueOf(c.get("emailAddress"));
                }
            }
            return null;
        } catch (Exception e) {
            log.debug("email lookup failed for {}: {}", partyId, e.getMessage());
            return null;
        }
    }

    static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest(value.getBytes(StandardCharsets.UTF_8))) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
