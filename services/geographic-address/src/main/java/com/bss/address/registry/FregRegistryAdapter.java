package com.bss.address.registry;

import com.bss.address.entity.RegistryConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.Map;

/**
 * Folkeregisteret (NO) — the built-in reference adapter. Speaks the person-
 * match wire (mock-freg in dev; the real base URL is Skatteetaten's shared
 * service or a distributor — config only). The credential is read from the
 * env var named by secretRef at call time, never stored.
 */
@Component
public class FregRegistryAdapter implements RegistryAdapter {

    private static final Logger log = LoggerFactory.getLogger(FregRegistryAdapter.class);

    private final RestClient.Builder builder;

    public FregRegistryAdapter(RestClient.Builder builder) {
        this.builder = builder;
    }

    @Override
    public String provider() {
        return "freg";
    }

    @Override
    @SuppressWarnings("unchecked")
    public Result match(RegistryConfig cfg, Person person, Map<String, Object> address) {
        try {
            String key = cfg.getSecretRef() == null ? "" : System.getenv().getOrDefault(cfg.getSecretRef(), "");
            Map<String, Object> body = new HashMap<>();
            body.put("name", person.name());
            if (person.birthDate() != null) {
                body.put("birthDate", person.birthDate());
            }
            body.put("address", address);
            Map<String, Object> resp = builder.clone().baseUrl(cfg.getBaseUrl()).build()
                    .post().uri("/folkeregisteret/api/personer/match")
                    .header("Authorization", "Bearer " + key)
                    .header("Content-Type", "application/json")
                    .body(body)
                    .retrieve()
                    .body(Map.class);
            if (resp == null || resp.get("result") == null) {
                return Result.of("unavailable");
            }
            return new Result(String.valueOf(resp.get("result")),
                    (Map<String, Object>) resp.get("registeredAddress"),
                    resp.get("movedDate") == null ? null : String.valueOf(resp.get("movedDate")));
        } catch (Exception e) {
            // Fail open: a registry outage must never block commerce — the
            // risk seam reads 'unavailable' and the operator's rules decide.
            log.warn("freg lookup unavailable: {}", e.getMessage());
            return Result.of("unavailable");
        }
    }
}
