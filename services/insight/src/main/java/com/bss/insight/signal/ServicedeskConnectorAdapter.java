package com.bss.insight.signal;

import com.bss.insight.dto.SignalInput;
import com.bss.insight.entity.SignalConnector;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

/**
 * The reference named adapter: a Zendesk-shaped support desk
 * (GET /api/v2/tickets.json). mock-servicedesk speaks this wire in dev; a
 * real desk is the same adapter with a real baseUrl + API token — config,
 * not code. Returns raw ticket text; the firewall runs downstream.
 */
@Component
public class ServicedeskConnectorAdapter implements SignalConnectorAdapter {

    private static final Logger log = LoggerFactory.getLogger(ServicedeskConnectorAdapter.class);

    private final RestClient.Builder builder;

    public ServicedeskConnectorAdapter(RestClient.Builder builder) {
        this.builder = builder;
    }

    @Override
    public String kind() {
        return "servicedesk";
    }

    @Override
    public List<SignalInput> pull(SignalConnector cfg) {
        List<SignalInput> out = new ArrayList<>();
        try {
            String token = cfg.getSecretRef() == null ? ""
                    : System.getenv().getOrDefault(cfg.getSecretRef(), "");
            JsonNode body = builder.clone().baseUrl(cfg.getBaseUrl()).build()
                    .get().uri("/api/v2/tickets.json")
                    .header("Authorization", "Bearer " + token)
                    .retrieve().body(JsonNode.class);
            JsonNode tickets = body == null ? null : body.path("tickets");
            if (tickets == null || !tickets.isArray()) {
                return out;
            }
            for (JsonNode t : tickets) {
                if (!t.hasNonNull("id")) {
                    continue;
                }
                String subject = t.path("subject").asText("");
                String description = t.path("description").asText("");
                String text = (subject + (description.isBlank() ? "" : " — " + description)).trim();
                if (text.isBlank()) {
                    continue;
                }
                ObjectNode context = JsonNodeFactory.instance.objectNode();
                if (t.hasNonNull("status")) context.put("status", t.get("status").asText());
                if (t.hasNonNull("priority")) context.put("priority", t.get("priority").asText());
                out.add(new SignalInput(null, text, "desk:" + t.get("id").asText(), null, null, null,
                        context.isEmpty() ? null : context));
            }
        } catch (Exception e) {
            // fail open like every provider seam — a desk outage must not
            // break the sync surface; the operator sees ingested=0
            log.warn("servicedesk pull failed: {}", e.getMessage());
        }
        return out;
    }
}
