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
            @Value("${bss.intelligence.base-url:}") String baseUrl,
            @Value("${bss.intelligence.api-key}") String apiKey,
            @Value("${bss.intelligence.model:}") String model) {
        // Deployment env often sets these to "" rather than leaving them unset
        // (compose passthrough) — blank must mean "use the default" too.
        String base = baseUrl == null || baseUrl.isBlank() ? "https://api.anthropic.com" : baseUrl;
        this.restClient = builder.baseUrl(base)
                .defaultHeader("x-api-key", apiKey)
                .defaultHeader("anthropic-version", "2023-06-01")
                .build();
        this.model = model == null || model.isBlank() ? "claude-haiku-4-5-20251001" : model;
    }

    @Override
    @SuppressWarnings("unchecked")
    public String complete(String system, String user) {
        Map<String, Object> response = restClient.post().uri("/v1/messages")
                .header("Content-Type", "application/json")
                .body(Map.of(
                        "model", model,
                        // Headroom for a full journey/campaign proposal JSON — 1024 truncates
                        // richer models (Sonnet/Opus) mid-object, breaking the JSON contract.
                        "max_tokens", 4096,
                        "system", system,
                        "messages", List.of(Map.of("role", "user", "content", user))))
                .retrieve().body(Map.class);
        // Concatenate every TEXT block (skip any thinking/tool blocks a model may emit),
        // rather than assuming the answer is content[0].
        List<Map<String, Object>> content = (List<Map<String, Object>>) response.get("content");
        StringBuilder text = new StringBuilder();
        if (content != null) {
            for (Map<String, Object> block : content) {
                Object t = block.get("text");
                if (t != null) {
                    text.append(t);
                }
            }
        }
        return text.toString();
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
