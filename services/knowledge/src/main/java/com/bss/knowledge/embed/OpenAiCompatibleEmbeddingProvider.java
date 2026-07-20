package com.bss.knowledge.embed;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/** A real embeddings model over the OpenAI-compatible wire — the same
 * dialect Ollama, vLLM and the hosted providers all speak. */
@Component
@ConditionalOnProperty(name = "bss.knowledge.embeddings", havingValue = "openai-compatible")
public class OpenAiCompatibleEmbeddingProvider implements EmbeddingProvider {

    private final RestClient rest;
    private final String model;

    public OpenAiCompatibleEmbeddingProvider(RestClient.Builder builder,
            @Value("${bss.knowledge.embeddings-url}") String baseUrl,
            @Value("${bss.knowledge.embeddings-key:}") String apiKey,
            @Value("${bss.knowledge.embeddings-model}") String model) {
        this.rest = builder.baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey).build();
        this.model = model;
    }

    @Override
    @SuppressWarnings("unchecked")
    public float[] embed(String text) {
        Map<String, Object> response = rest.post().uri("/v1/embeddings")
                .header("Content-Type", "application/json")
                .body(Map.of("model", model, "input", text))
                .retrieve().body(Map.class);
        List<Number> values = (List<Number>) ((Map<String, Object>)
                ((List<Object>) response.get("data")).get(0)).get("embedding");
        float[] v = new float[values.size()];
        for (int i = 0; i < v.length; i++) {
            v[i] = values.get(i).floatValue();
        }
        return v;
    }
}
