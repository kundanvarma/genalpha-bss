package com.bss.intelligence.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The structural half of "the BSS explains itself": what a console page manages
 * and which governed actions it offers, from the Operational Semantic Registry,
 * read with the ASKER's bearer. Fail-open — no registry, no entry, no words.
 */
@Component
public class OntologyClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public OntologyClient(RestClient.Builder builder, ObjectMapper objectMapper,
            @Value("${bss.downstream.ontology-base-url:http://localhost:8160}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
        this.objectMapper = objectMapper;
    }

    /** A synthetic article for the page, or null when the ontology has no entry for it. */
    public Map<String, Object> pageArticle(String bearerToken, String pagePath) {
        try {
            String body = restClient.get()
                    .uri("/ontology/v1/explain/page/" + pagePath)
                    .header("Authorization", "Bearer " + bearerToken)
                    .retrieve().body(String.class);
            if (body == null) {
                return null;
            }
            JsonNode n = objectMapper.readTree(body);
            if (!n.path("known").asBoolean(false)) {
                return null;
            }
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("id", "ontology:page:" + pagePath);
            a.put("title", "What this page manages and what can be done here (from the operational ontology)");
            a.put("body", n.path("text").asText());
            a.put("tags", "ontology, pane:" + pagePath);
            a.put("lastUpdate", "registry");
            return a;
        } catch (Exception unavailable) {
            return null;
        }
    }
}
