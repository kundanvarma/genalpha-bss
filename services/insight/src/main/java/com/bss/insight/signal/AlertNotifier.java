package com.bss.insight.signal;

import com.bss.insight.entity.SignalConnector;
import com.bss.insight.repository.SignalConnectorRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * The act half of SI-P5: a VoC early warning goes where the operator's team
 * already lives. Connectors of kind 'slack-webhook' / mode 'notify' carry a
 * SECRET webhook URL (an env-var name — a Slack incoming-webhook URL IS a
 * credential) and receive the Slack-shaped {text} payload; Teams and friends
 * are the same connector with a different URL. Fail open per webhook — an
 * unreachable chat tool must never take the sweep down.
 */
@Component
public class AlertNotifier {

    private static final Logger log = LoggerFactory.getLogger(AlertNotifier.class);

    private final SignalConnectorRepository connectors;
    private final RestClient.Builder builder;

    public AlertNotifier(SignalConnectorRepository connectors, RestClient.Builder builder) {
        this.connectors = connectors;
        this.builder = builder;
    }

    /** @return how many notify-connectors accepted the text. */
    public int notify(String tenantId, String text) {
        int delivered = 0;
        for (SignalConnector c : connectors.findByTenantIdOrderByNameAsc(tenantId)) {
            if (!c.isEnabled() || !"notify".equals(c.getMode())) {
                continue;
            }
            String url = c.getSecretRef() == null ? ""
                    : System.getenv().getOrDefault(c.getSecretRef(), "");
            if (url.isBlank()) {
                continue;
            }
            try {
                builder.clone().build().post().uri(url)
                        .header("Content-Type", "application/json")
                        .body(Map.of("text", text))
                        .retrieve().toBodilessEntity();
                delivered++;
            } catch (Exception e) {
                log.warn("alert webhook '{}' unreachable: {}", c.getName(), e.getMessage());
            }
        }
        return delivered;
    }
}
