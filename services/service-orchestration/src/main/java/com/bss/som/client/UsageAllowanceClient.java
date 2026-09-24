package com.bss.som.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static com.bss.som.mapper.Wire.idOf;

/**
 * The overage tier table the usage component holds for an offering (TMF635 allowance with
 * `overageTier`), read with the SOM's machine identity so activation can push the same steps to
 * the charging system. Fail-open: no usage component, no allowance, or an outage all mean "flat".
 */
@Component
public class UsageAllowanceClient {

    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST = new ParameterizedTypeReference<>() { };
    private final RestClient client;

    public UsageAllowanceClient(RestClient.Builder builder, MachineTokenInterceptor tokenInterceptor,
            @Value("${bss.downstream.usage-base-url:}") String baseUrl) {
        this.client = baseUrl == null || baseUrl.isBlank() ? null
                : builder.clone().baseUrl(baseUrl).requestInterceptor(tokenInterceptor).build();
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> tiersOf(String offeringId) {
        if (client == null || offeringId == null) {
            return List.of();
        }
        try {
            List<Map<String, Object>> rows = client.get().uri("/tmf-api/usageManagement/v4/usageAllowance?limit=100")
                    .retrieve().body(LIST);
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> a : rows == null ? List.<Map<String, Object>>of() : rows) {
                if (offeringId.equals(idOf(a.get("productOffering")))
                        && a.get("overageTier") instanceof List<?> tiers) {
                    for (Object t : tiers) {
                        if (t instanceof Map<?, ?> m) {
                            out.add((Map<String, Object>) m);
                        }
                    }
                }
            }
            return out;
        } catch (RuntimeException e) {
            return List.of();
        }
    }
}
