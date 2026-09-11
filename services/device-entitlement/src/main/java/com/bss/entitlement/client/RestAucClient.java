package com.bss.entitlement.client;

import com.bss.entitlement.security.TenantRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The dev/reference AUC adapter: mock-hss's REST shape ({@code POST
 * /auc/vector}, {@code GET /subscribers?iccid=}, {@code POST
 * /subscribers/allocate}). Per tenant: the tenant's {@code auc-base-url} (+
 * bearer {@code auc-token}) from tenants.yml, else the deployment default.
 */
@Component
public class RestAucClient implements AucClient {

    private static final Logger log = LoggerFactory.getLogger(RestAucClient.class);

    private final RestClient.Builder builder;
    private final TenantRegistry tenants;
    private final String defaultBaseUrl;
    private final String defaultToken;
    private final Map<String, RestClient> clients = new ConcurrentHashMap<>();

    public RestAucClient(RestClient.Builder builder, TenantRegistry tenants,
            @Value("${bss.downstream.auc-base-url:}") String defaultBaseUrl,
            @Value("${bss.downstream.auc-token:}") String defaultToken) {
        this.builder = builder;
        this.tenants = tenants;
        this.defaultBaseUrl = defaultBaseUrl == null ? "" : defaultBaseUrl.trim();
        this.defaultToken = defaultToken == null ? "" : defaultToken;
    }

    private String[] binding(String tenantId) {
        TenantRegistry.TenantEntry t = tenants.byId(tenantId);
        String url = t == null || t.getAucBaseUrl() == null || t.getAucBaseUrl().isBlank() ? defaultBaseUrl : t.getAucBaseUrl().trim();
        String token = t == null || t.getAucBaseUrl() == null || t.getAucBaseUrl().isBlank() ? defaultToken
                : (t.getAucToken() == null ? "" : t.getAucToken());
        return new String[] { url, token };
    }

    @Override
    public boolean enabled(String tenantId) {
        return !binding(tenantId)[0].isBlank();
    }

    private RestClient client(String tenantId) {
        String[] b = binding(tenantId);
        if (b[0].isBlank()) {
            return null;
        }
        return clients.computeIfAbsent(b[0] + "|" + b[1], k -> {
            RestClient.Builder cb = builder.clone().baseUrl(b[0]);
            if (!b[1].isBlank()) {
                cb.defaultHeader("Authorization", "Bearer " + b[1]);
            }
            return cb.build();
        });
    }

    @Override
    @SuppressWarnings("unchecked")
    public Optional<Vector> vector(String tenantId, String imsi) {
        RestClient c = client(tenantId);
        if (c == null) {
            return Optional.empty();
        }
        try {
            Map<String, Object> v = c.post().uri("/auc/vector")
                    .header("Content-Type", "application/json").body(Map.of("imsi", imsi))
                    .retrieve().body(Map.class);
            if (v == null || v.get("rand") == null) {
                return Optional.empty();
            }
            return Optional.of(new Vector(s(v.get("rand")), s(v.get("autn")), s(v.get("xres")), s(v.get("ck")), s(v.get("ik"))));
        } catch (HttpClientErrorException e) {
            return Optional.empty();
        } catch (RuntimeException e) {
            log.warn("AUC for tenant {} unreachable for IMSI {}: {}", tenantId, imsi, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public Optional<Identity> identity(String tenantId, String imsi) {
        RestClient c = client(tenantId);
        if (c == null) {
            return Optional.empty();
        }
        try {
            Map<String, Object> v = c.get().uri("/subscribers/{imsi}", imsi).retrieve().body(Map.class);
            return v == null ? Optional.empty() : Optional.of(new Identity(imsi, s(v.get("iccid")), s(v.get("msisdn"))));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public Optional<Identity> identityByIccid(String tenantId, String iccid, String msisdn) {
        RestClient c = client(tenantId);
        if (c == null || iccid == null || iccid.isBlank()) {
            return Optional.empty();
        }
        try {
            List<Map<String, Object>> list = c.get().uri("/subscribers?iccid={i}", iccid).retrieve().body(List.class);
            if (list != null && !list.isEmpty() && list.get(0).get("imsi") != null) {
                Map<String, Object> v = list.get(0);
                return Optional.of(new Identity(s(v.get("imsi")), s(v.get("iccid")), s(v.get("msisdn"))));
            }
            // a dev AUC allocates an identity for a SIM it has not seen; a real HSS already knows it
            Map<String, Object> v = c.post().uri("/subscribers/allocate")
                    .header("Content-Type", "application/json")
                    .body(msisdn == null ? Map.of("iccid", iccid) : Map.of("iccid", iccid, "msisdn", msisdn))
                    .retrieve().body(Map.class);
            return v == null || v.get("imsi") == null ? Optional.empty()
                    : Optional.of(new Identity(s(v.get("imsi")), s(v.get("iccid")), s(v.get("msisdn"))));
        } catch (RuntimeException e) {
            log.warn("AUC for tenant {} could not resolve ICCID {}: {}", tenantId, iccid, e.getMessage());
            return Optional.empty();
        }
    }

    private static String s(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
