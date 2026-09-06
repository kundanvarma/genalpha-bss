package com.bss.insight.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Organic publishing: put a post OUT on the brand's own handle — the outbound,
 * broadcast side of social (distinct from a per-customer message). Proxies the
 * brand handle's post feed on the platform; the platform is the record.
 */
@Service
public class SocialPublishService {

    private final com.bss.insight.social.SocialProviders providers;

    public SocialPublishService(com.bss.insight.social.SocialProviders providers) {
        this.providers = providers;
    }

    public Map<String, Object> publish(String content) {
        com.bss.insight.social.SocialConfig cfg = providers.current();
        if (!cfg.enabled()) {
            return Map.of("published", false, "reason", "no social handle configured for this tenant");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content is required to publish");
        }
        Map<String, Object> res = providers.providerFor(cfg).publish(cfg, content);
        return Map.of("published", true, "id", String.valueOf(res.getOrDefault("id", "")),
                "permalink", String.valueOf(res.getOrDefault("permalink", "")), "provider", cfg.providerName());
    }

    public List<Map<String, Object>> posts() {
        com.bss.insight.social.SocialConfig cfg = providers.current();
        if (!cfg.enabled()) {
            return List.of();
        }
        return providers.providerFor(cfg).posts(cfg);
    }
}
