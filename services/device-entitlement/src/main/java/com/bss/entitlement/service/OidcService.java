package com.bss.entitlement.service;

import com.bss.entitlement.entity.EntitlementSubscriber;
import com.bss.entitlement.repository.EntitlementSubscriberRepository;
import com.bss.entitlement.security.TenantRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * TS.43 §2.8.2 — authentication with OAuth 2.0 / OpenID Connect, for clients
 * that cannot reach the SIM (a tablet's companion flow, a desktop web sheet,
 * an app without carrier privileges): the ECS answers {@code 302 Found} to the
 * tenant's OIDC authorize endpoint, the end-user signs in, the code comes
 * back to {@code /ts43/oidc/callback}, the ECS exchanges it over the
 * backchannel, identifies the subscription by the authenticated party, mints
 * its own token and resumes the original request. The original query rides
 * in a signed {@code state}; the OIDC client is the confidential
 * {@code bss-ecs} client of each realm.
 */
@Service
public class OidcService {

    private static final Logger log = LoggerFactory.getLogger(OidcService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    public record Resumed(String tenantId, String query, String imsi, String token) { }

    private final TenantRegistry tenants;
    private final EntitlementSubscriberRepository subscribers;
    private final TokenService tokens;
    private final RestClient restClient;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String clientId;
    private final String clientSecret;
    private final String publicBaseUrl;

    public OidcService(TenantRegistry tenants, EntitlementSubscriberRepository subscribers, TokenService tokens,
            RestClient.Builder builder,
            @Value("${bss.entitlement.oidc-client-id:bss-ecs}") String clientId,
            @Value("${bss.entitlement.oidc-client-secret:}") String clientSecret,
            @Value("${bss.entitlement.public-base-url:http://localhost:8080}") String publicBaseUrl) {
        this.tenants = tenants;
        this.subscribers = subscribers;
        this.tokens = tokens;
        this.restClient = builder.build();
        this.clientId = clientId;
        this.clientSecret = clientSecret == null ? "" : clientSecret;
        this.publicBaseUrl = publicBaseUrl.replaceAll("/+$", "");
    }

    public boolean available(String tenantId) {
        TenantRegistry.TenantEntry t = tenants.byId(tenantId);
        return !clientSecret.isBlank() && t != null && t.getIssuer() != null && t.getTokenUri() != null;
    }

    public String callbackUrl() {
        return publicBaseUrl + "/ts43/oidc/callback";
    }

    /** The 302 Location for a request that must authenticate through OIDC. */
    public String authorizeUrl(String tenantId, String originalQuery) {
        TenantRegistry.TenantEntry t = tenants.byId(tenantId);
        String nonce = random();
        String state = sign(tenantId + "|" + nonce + "|" + (originalQuery == null ? "" : originalQuery));
        return t.getIssuer().replaceAll("/+$", "") + "/protocol/openid-connect/auth"
                + "?response_type=code&scope=openid"
                + "&client_id=" + enc(clientId)
                + "&redirect_uri=" + enc(callbackUrl())
                + "&state=" + enc(state)
                + "&nonce=" + enc(nonce);
    }

    /** Exchange the code, find the subscription, mint the ECS token. Empty = refused. */
    @Transactional
    @SuppressWarnings("unchecked")
    public Optional<Resumed> callback(String code, String state) {
        String[] parts = verify(state);
        if (parts == null) {
            return Optional.empty();
        }
        String tenantId = parts[0];
        String query = parts[2];
        TenantRegistry.TenantEntry t = tenants.byId(tenantId);
        if (t == null) {
            return Optional.empty();
        }
        try {
            String form = "grant_type=authorization_code&code=" + enc(code) + "&redirect_uri=" + enc(callbackUrl())
                    + "&client_id=" + enc(clientId) + "&client_secret=" + enc(clientSecret);
            Map<String, Object> reply = restClient.post().uri(t.getTokenUri())
                    .header("Content-Type", "application/x-www-form-urlencoded").body(form)
                    .retrieve().body(Map.class);
            String idToken = reply == null ? null : (String) reply.get("id_token");
            String accessToken = reply == null ? null : (String) reply.get("access_token");
            String subject = subjectOf(idToken != null ? idToken : accessToken);
            if (subject == null) {
                return Optional.empty();
            }
            // the party IS the authenticated user (personas are pinned to their party ids)
            EntitlementSubscriber s = subscribers.findByTenantIdAndPartyId(tenantId, subject).stream()
                    .filter(x -> EntitlementSubscriber.ACTIVE.equals(x.getStatus())).findFirst()
                    .orElse(subscribers.findByTenantIdAndPartyId(tenantId, subject).stream().findFirst().orElse(null));
            if (s == null) {
                log.info("OIDC: party {} of tenant {} has no bound line", subject, tenantId);
                return Optional.empty();
            }
            String ecsToken = tokens.issue(tenantId, s.getImsi(), terminalIdOf(query)).getToken();
            return Optional.of(new Resumed(tenantId, query, s.getImsi(), ecsToken));
        } catch (RuntimeException e) {
            log.warn("OIDC code exchange failed for tenant {}: {}", tenantId, e.getMessage());
            return Optional.empty();
        }
    }

    /** The URL the browser resumes on: the original request plus the ECS token. */
    public String resumeUrl(Resumed r) {
        String q = r.query() == null || r.query().isBlank() ? "" : r.query() + "&";
        return publicBaseUrl + "/ts43?" + q + "token=" + enc(r.token());
    }

    private static String terminalIdOf(String query) {
        if (query == null) {
            return null;
        }
        for (String kv : query.split("&")) {
            if (kv.startsWith("terminal_id=")) {
                return java.net.URLDecoder.decode(kv.substring("terminal_id=".length()), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private String subjectOf(String jwt) {
        if (jwt == null) {
            return null;
        }
        try {
            String[] p = jwt.split("\\.");
            Map<String, Object> claims = mapper.readValue(Base64.getUrlDecoder().decode(p[1]), Map.class);
            return claims.get("sub") == null ? null : String.valueOf(claims.get("sub"));
        } catch (Exception e) {
            return null;
        }
    }

    private String sign(String payload) {
        String b = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return b + "." + hmac(b);
    }

    private String[] verify(String state) {
        if (state == null || !state.contains(".")) {
            return null;
        }
        String b = state.substring(0, state.lastIndexOf('.'));
        String mac = state.substring(state.lastIndexOf('.') + 1);
        if (!MessageDigest.isEqual(mac.getBytes(StandardCharsets.UTF_8), hmac(b).getBytes(StandardCharsets.UTF_8))) {
            return null;
        }
        String payload = new String(Base64.getUrlDecoder().decode(b), StandardCharsets.UTF_8);
        String[] parts = payload.split("\\|", 3);
        return parts.length == 3 ? parts : null;
    }

    private String hmac(String s) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(("ecs-state:" + clientSecret).getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String random() {
        byte[] b = new byte[16];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    public Map<String, Object> describe(String tenantId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("available", available(tenantId));
        m.put("clientId", clientId);
        m.put("callback", callbackUrl());
        return m;
    }
}
