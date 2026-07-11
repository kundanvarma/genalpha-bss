package com.bss.intelligence.llm;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/** Native Claude dialect (Messages API) for operators who run Anthropic models. */
@Component
@ConditionalOnProperty(name = "bss.intelligence.provider", havingValue = "anthropic")
public class AnthropicAdapter implements LlmAdapter {

    private final RestClient restClient;
    private final String model;

    public AnthropicAdapter(RestClient.Builder builder,
            @Value("${bss.intelligence.base-url:https://api.anthropic.com}") String baseUrl,
            @Value("${bss.intelligence.api-key}") String apiKey,
            @Value("${bss.intelligence.model:claude-haiku-4-5-20251001}") String model) {
        this.restClient = builder.baseUrl(baseUrl)
                .defaultHeader("x-api-key", apiKey)
                .defaultHeader("anthropic-version", "2023-06-01")
                .build();
        this.model = model;
    }

    @Override
    @SuppressWarnings("unchecked")
    public String complete(String system, String user) {
        Map<String, Object> response = restClient.post().uri("/v1/messages")
                .header("Content-Type", "application/json")
                .body(Map.of(
                        "model", model,
                        "max_tokens", 1024,
                        "system", system,
                        "messages", List.of(Map.of("role", "user", "content", user))))
                .retrieve().body(Map.class);
        List<Map<String, Object>> content = (List<Map<String, Object>>) response.get("content");
        return String.valueOf(content.get(0).get("text"));
    }

    @Override
    public String provider() {
        return "anthropic";
    }

    @Override
    public String model() {
        return model;
    }
}
