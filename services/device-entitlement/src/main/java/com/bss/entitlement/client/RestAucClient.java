package com.bss.entitlement.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.Optional;

/** The dev/reference AUC adapter: mock-hss's REST shape ({@code POST /auc/vector}). */
@Component
public class RestAucClient implements AucClient {

    private static final Logger log = LoggerFactory.getLogger(RestAucClient.class);

    private final RestClient restClient;
    private final boolean enabled;

    public RestAucClient(RestClient.Builder builder, @Value("${bss.downstream.auc-base-url:}") String baseUrl) {
        this.enabled = baseUrl != null && !baseUrl.isBlank();
        this.restClient = enabled ? builder.baseUrl(baseUrl).build() : null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Optional<Vector> vector(String imsi) {
        if (!enabled) {
            return Optional.empty();
        }
        try {
            Map<String, Object> v = restClient.post().uri("/auc/vector")
                    .header("Content-Type", "application/json").body(Map.of("imsi", imsi))
                    .retrieve().body(Map.class);
            if (v == null || v.get("rand") == null) {
                return Optional.empty();
            }
            return Optional.of(new Vector(s(v.get("rand")), s(v.get("autn")), s(v.get("xres")), s(v.get("ck")), s(v.get("ik"))));
        } catch (HttpClientErrorException e) {
            return Optional.empty();
        } catch (RuntimeException e) {
            log.warn("AUC unreachable for IMSI {}: {}", imsi, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public Optional<Identity> identity(String imsi) {
        if (!enabled) {
            return Optional.empty();
        }
        try {
            Map<String, Object> v = restClient.get().uri("/subscribers/{imsi}", imsi).retrieve().body(Map.class);
            return v == null ? Optional.empty() : Optional.of(new Identity(imsi, s(v.get("iccid")), s(v.get("msisdn"))));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static String s(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
