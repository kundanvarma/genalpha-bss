package com.bss.insight.signal;

import com.bss.insight.entity.SignalConnector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> pull(SignalConnector cfg) {
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            String token = cfg.getSecretRef() == null ? ""
                    : System.getenv().getOrDefault(cfg.getSecretRef(), "");
            Map<String, Object> body = builder.clone().baseUrl(cfg.getBaseUrl()).build()
                    .get().uri("/api/v2/tickets.json")
                    .header("Authorization", "Bearer " + token)
                    .retrieve().body(Map.class);
            List<Map<String, Object>> tickets = body != null && body.get("tickets") instanceof List<?> l
                    ? (List<Map<String, Object>>) l : List.of();
            for (Map<String, Object> t : tickets) {
                if (t.get("id") == null) {
                    continue;
                }
                String subject = t.get("subject") == null ? "" : String.valueOf(t.get("subject"));
                String description = t.get("description") == null ? "" : String.valueOf(t.get("description"));
                String text = (subject + (description.isBlank() ? "" : " — " + description)).trim();
                if (text.isBlank()) {
                    continue;
                }
                Map<String, Object> dto = new LinkedHashMap<>();
                dto.put("sourceRef", "desk:" + t.get("id"));
                dto.put("text", text);
                Map<String, Object> context = new LinkedHashMap<>();
                if (t.get("status") != null) context.put("status", String.valueOf(t.get("status")));
                if (t.get("priority") != null) context.put("priority", String.valueOf(t.get("priority")));
                if (!context.isEmpty()) dto.put("context", context);
                out.add(dto);
            }
        } catch (Exception e) {
            // fail open like every provider seam — a desk outage must not
            // break the sync surface; the operator sees ingested=0
            log.warn("servicedesk pull failed: {}", e.getMessage());
        }
        return out;
    }
}
