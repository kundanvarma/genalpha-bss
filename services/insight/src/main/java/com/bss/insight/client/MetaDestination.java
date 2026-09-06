package com.bss.insight.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Meta Custom Audiences through the tenant's social adapter (Graph API payload, or the dev shape). */
@Component
public class MetaDestination implements AdDestination {

    private static final Logger log = LoggerFactory.getLogger(MetaDestination.class);
    private static final int BATCH = 500;

    private final com.bss.insight.social.SocialProviders providers;

    public MetaDestination(com.bss.insight.social.SocialProviders providers) {
        this.providers = providers;
    }

    public String name() { return "meta"; }

    /** Per tenant: the current tenant's social line (or the deployment fallback) has a url. */
    public boolean enabled() { return providers.current().audiencesEnabled(); }

    /** Batches of 500 through the tenant's adapter: the Graph payload for 'meta', the dev shape for 'mock'. */
    public int push(String externalAudienceId, List<String> hashedEmails) {
        com.bss.insight.social.SocialConfig cfg = providers.current();
        if (!cfg.audiencesEnabled() || hashedEmails.isEmpty()) return 0;
        com.bss.insight.social.SocialProvider provider = providers.providerFor(cfg);
        int sent = 0;
        for (int i = 0; i < hashedEmails.size(); i += BATCH) {
            List<String> batch = hashedEmails.subList(i, Math.min(i + BATCH, hashedEmails.size()));
            try {
                sent += provider.pushAudience(cfg, externalAudienceId, batch);
            } catch (Exception e) {
                log.warn("meta push batch failed ({} rows): {}", batch.size(), e.getMessage());
            }
        }
        return sent;
    }
}
