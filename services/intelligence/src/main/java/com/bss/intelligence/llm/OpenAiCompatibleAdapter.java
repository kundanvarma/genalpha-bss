package com.bss.intelligence.llm;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * The de-facto interop dialect: one base-url + key + model reaches OpenAI,
 * Azure OpenAI, Mistral, Groq, vLLM and Ollama alike. This is the default
 * provider because it is the one that keeps the "any LLM" claim honest.
 */
@Component
@ConditionalOnProperty(name = "bss.intelligence.provider", havingValue = "openai-compatible")
public class OpenAiCompatibleAdapter implements LlmAdapter {

    private final RestClient restClient;
    private final String model;

    public OpenAiCompatibleAdapter(RestClient.Builder builder,
            @Value("${bss.intelligence.base-url}") String baseUrl,
            @Value("${bss.intelligence.api-key:}") String apiKey,
            @Value("${bss.intelligence.model}") String model) {
        RestClient.Builder b = builder.baseUrl(baseUrl);
        if (!apiKey.isBlank()) {
            b = b.defaultHeader("Authorization", "Bearer " + apiKey);
        }
        this.restClient = b.build();
        this.model = model;
    }

    @Override
    public String complete(String system, String user) {
        return completeWithUsage(null, system, user).text();
    }

    @Override
    @SuppressWarnings("unchecked")
    public LlmCompletion completeWithUsage(Tier tier, String system, String user) {
        Map<String, Object> response = restClient.post().uri("/v1/chat/completions")
                .header("Content-Type", "application/json")
                .body(Map.of(
                        "model", model,
                        "messages", List.of(
                                Map.of("role", "system", "content", system),
                                Map.of("role", "user", "content", user))))
                .retrieve().body(Map.class);
        List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
        Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
        return new LlmCompletion(String.valueOf(message.get("content")), usage(response));
    }

    /** Chat Completions usage: prompt and completion tokens; null when the server leaves it off. */
    static LlmCompletion.Usage usage(Map<?, ?> response) {
        Object u = response == null ? null : response.get("usage");
        if (!(u instanceof Map<?, ?> usage)) {
            return null;
        }
        Integer in = AnthropicAdapter.count(usage.get("prompt_tokens"));
        Integer out = AnthropicAdapter.count(usage.get("completion_tokens"));
        if (in == null && out == null) {
            return null;
        }
        return new LlmCompletion.Usage(in == null ? 0 : in, out == null ? 0 : out);
    }

    @Override
    public String provider() {
        return "openai-compatible";
    }

    @Override
    public String model() {
        return model;
    }
}
