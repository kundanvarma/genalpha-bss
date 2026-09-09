package com.bss.catalog.service;

import com.bss.catalog.exception.BadRequestException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * The registered sales channels — the ONLY values a ProductOffering.channel
 * may carry, so "webapp" or "mobileapp" cannot be configured by hand. Every
 * front end names itself with the X-Channel header; a caller without one is
 * the web shop. Agents are channels too (ACP, MCP, A2A), so an operator can
 * hold an offer back from agents exactly as from dealers.
 */
@Component
public class Channels {

    public static final String HEADER = "X-Channel";
    public static final String DEFAULT = "web";
    public static final List<Map<String, String>> REGISTERED = List.of(
            Map.of("id", "web", "name", "Web shop"),
            Map.of("id", "app", "name", "Mobile app"),
            Map.of("id", "store", "name", "Store / dealer"),
            Map.of("id", "telesales", "name", "Telesales"),
            Map.of("id", "care", "name", "Care (assisted)"),
            Map.of("id", "business", "name", "Business console"),
            Map.of("id", "partner", "name", "Partner portal"),
            Map.of("id", "agent-acp", "name", "AI agents via ACP"),
            Map.of("id", "agent-mcp", "name", "AI agents via MCP"),
            Map.of("id", "agent-a2a", "name", "AI agents via A2A"));

    public static boolean known(String id) {
        return id != null && REGISTERED.stream().anyMatch(c -> c.get("id").equals(id));
    }

    /** Validate a channel list from a DTO: every entry must be a registered id. */
    public static void requireKnown(List<Map<String, Object>> channel) {
        if (channel == null) {
            return;
        }
        for (Map<String, Object> c : channel) {
            String id = c == null || c.get("id") == null ? null : String.valueOf(c.get("id"));
            if (!known(id)) {
                throw new BadRequestException("unknown channel '" + id + "' — the registered channels are "
                        + REGISTERED.stream().map(c2 -> c2.get("id")).toList());
            }
        }
    }

    /** The channel the current request speaks for, when it says so. */
    public Optional<String> requested() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            String h = attrs.getRequest().getHeader(HEADER);
            if (h != null && !h.isBlank()) {
                return Optional.of(h.trim().toLowerCase());
            }
        }
        return Optional.empty();
    }
}
