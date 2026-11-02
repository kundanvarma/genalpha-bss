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
    public String complete(String system, String user) {
        return completeWithUsage(null, system, user).text();
    }

    @Override
    @SuppressWarnings("unchecked")
    public LlmCompletion completeWithUsage(Tier tier, String system, String user) {
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
        return new LlmCompletion(text.toString(), usage(response));
    }

    /** Messages API usage: input, plus the cached-prompt tokens it billed apart; null when absent. */
    static LlmCompletion.Usage usage(Map<?, ?> response) {
        Object u = response == null ? null : response.get("usage");
        if (!(u instanceof Map<?, ?> usage)) {
            return null;
        }
        Integer in = count(usage.get("input_tokens"));
        Integer out = count(usage.get("output_tokens"));
        if (in == null && out == null) {
            return null;
        }
        int cached = orZero(count(usage.get("cache_creation_input_tokens")))
                + orZero(count(usage.get("cache_read_input_tokens")));
        return new LlmCompletion.Usage(orZero(in) + cached, orZero(out));
    }

    static Integer count(Object v) {
        return v instanceof Number n ? n.intValue() : null;
    }

    private static int orZero(Integer v) {
        return v == null ? 0 : v;
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
