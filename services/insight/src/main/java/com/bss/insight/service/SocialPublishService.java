package com.bss.insight.service;

import com.bss.insight.dto.PublishResult;
import com.bss.insight.dto.PublishedPost;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.util.List;

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

    public PublishResult publish(String content) {
        com.bss.insight.social.SocialConfig cfg = providers.current();
        if (!cfg.enabled()) {
            return PublishResult.NotPublished.because("no social handle configured for this tenant");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content is required to publish");
        }
        PublishedPost res = providers.providerFor(cfg).publish(cfg, content);
        return PublishResult.Published.of(res.id() == null ? "" : res.id(),
                res.permalink() == null ? "" : res.permalink(), cfg.providerName());
    }

    /** The platform's own post documents, verbatim. */
    public List<JsonNode> posts() {
        com.bss.insight.social.SocialConfig cfg = providers.current();
        if (!cfg.enabled()) {
            return List.of();
        }
        return providers.providerFor(cfg).posts(cfg);
    }
}
