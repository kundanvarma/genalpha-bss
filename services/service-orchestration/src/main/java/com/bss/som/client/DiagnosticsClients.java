package com.bss.som.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What a LINE DIAGNOSIS needs to see, fail-open on every leg: the open
 * network problems (assurance) and the line's data bucket (the OCS). A
 * diagnosis that cannot check something says so — it never invents an
 * all-clear and never 500s the person asking for help.
 */
@Component
public class DiagnosticsClients {

    private static final Logger log = LoggerFactory.getLogger(DiagnosticsClients.class);

    private final RestClient assurance;
    private final RestClient ocs;
    private final boolean ocsEnabled;

    public DiagnosticsClients(RestClient.Builder builder, MachineTokenInterceptor tokenInterceptor,
            @Value("${bss.downstream.assurance-base-url:}") String assuranceBaseUrl,
            @Value("${bss.downstream.ocs-base-url:}") String ocsBaseUrl) {
        this.assurance = assuranceBaseUrl == null || assuranceBaseUrl.isBlank() ? null
                : builder.baseUrl(assuranceBaseUrl).requestInterceptor(tokenInterceptor).build();
        this.ocsEnabled = ocsBaseUrl != null && !ocsBaseUrl.isBlank();
        this.ocs = ocsEnabled ? builder.baseUrl(ocsBaseUrl).build() : null;
    }

    /** Open service problems; empty Optional = could not check. */
    @SuppressWarnings("unchecked")
    public Optional<List<Map<String, Object>>> openProblems() {
        if (assurance == null) {
            return Optional.of(List.of());
        }
        try {
            List<Map<String, Object>> problems = assurance.get()
                    .uri("/tmf-api/serviceProblemManagement/v4/serviceProblem?status=open")
                    .retrieve().body(List.class);
            return Optional.of(problems == null ? List.of() : problems);
        } catch (Exception e) {
            log.warn("diagnosis could not reach assurance: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** The line's data bucket at the charging master; empty = none/unknown. */
    @SuppressWarnings("unchecked")
    public Optional<Map<String, Object>> bucketOf(String tenantId, String serviceId) {
        if (!ocsEnabled) {
            return Optional.empty();
        }
        try {
            List<Map<String, Object>> subs = ocs.get()
                    .uri("/subscribers?tenantId={t}", tenantId)
                    .retrieve().body(List.class);
            return subs == null ? Optional.empty() : subs.stream()
                    .filter(s -> serviceId.equals(String.valueOf(s.get("serviceId"))))
                    .findFirst()
                    .flatMap(s -> s.get("buckets") instanceof List<?> buckets && !buckets.isEmpty()
                            ? Optional.of((Map<String, Object>) buckets.get(0)) : Optional.empty());
        } catch (Exception e) {
            log.warn("diagnosis could not reach the OCS: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
