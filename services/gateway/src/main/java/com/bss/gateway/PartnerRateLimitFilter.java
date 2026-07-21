package com.bss.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RATE LIMITS, two rings. The strict ring: per-partner buckets on the
 * dealer surface — a chain's runaway POS (a retry storm, a bad deploy
 * on THEIR side) must never crowd out the other chains. The wide ring:
 * a generous fleet-wide ceiling on EVERY path — per subject for people,
 * per client for machines, per IP for anonymous knocks — so no single
 * caller, credentialed or not, can flood the gateway unmetered. Buckets
 * are fairness, not authz: the resource services still validate every
 * token; this filter only decides who has been knocking too fast.
 */
@Component
public class PartnerRateLimitFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(PartnerRateLimitFilter.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final int capacity;
    private final long windowMs;
    private final int globalCapacity;
    private final long globalWindowMs;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    record Window(long startedAt, int count) {
    }

    public PartnerRateLimitFilter(
            @Value("${bss.gateway.partner-rate.capacity:60}") int capacity,
            @Value("${bss.gateway.partner-rate.window-ms:60000}") long windowMs,
            @Value("${bss.gateway.global-rate.capacity:1200}") int globalCapacity,
            @Value("${bss.gateway.global-rate.window-ms:60000}") long globalWindowMs) {
        this.capacity = capacity;
        this.windowMs = windowMs;
        this.globalCapacity = globalCapacity;
        this.globalWindowMs = globalWindowMs;
    }

    @Override
    public int getOrder() {
        return -50; // before routing — a refused knock costs no backend call
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        boolean dealerSurface = path.startsWith("/dealer/v1/");
        // the strict ring for partners, the wide ring for everyone
        String key = dealerSurface ? partnerKey(exchange) : "g:" + subjectKey(exchange);
        long retryAfterSeconds = dealerSurface
                ? tryAcquire(key, capacity, windowMs)
                : tryAcquire(key, globalCapacity, globalWindowMs);
        if (retryAfterSeconds == 0) {
            return chain.filter(exchange);
        }
        log.info("rate limit: {} refused on {} (retry in {}s)", key, path, retryAfterSeconds);
        var response = exchange.getResponse();
        response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        response.getHeaders().add("Retry-After", String.valueOf(retryAfterSeconds));
        byte[] body = ("{\"error\":\"rate limit exceeded — retry after "
                + retryAfterSeconds + "s\"}").getBytes(StandardCharsets.UTF_8);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
    }

    /** 0 = admitted; otherwise seconds until the window resets. */
    synchronized long tryAcquire(String key, int bucketCapacity, long bucketWindowMs) {
        long now = System.currentTimeMillis();
        Window window = windows.get(key);
        if (window == null || now - window.startedAt() >= bucketWindowMs) {
            windows.put(key, new Window(now, 1));
            return 0;
        }
        if (window.count() < bucketCapacity) {
            windows.put(key, new Window(window.startedAt(), window.count() + 1));
            return 0;
        }
        return Math.max(1, (window.startedAt() + bucketWindowMs - now + 999) / 1000);
    }

    /** The OAuth2 client from the bearer's azp claim — decode-only, no
     * verification: fairness bucketing, never authorization. */
    static String partnerKey(ServerWebExchange exchange) {
        String claimed = claimKey(exchange, false);
        return claimed != null ? claimed : ipKey(exchange);
    }

    /** The wide ring's key: the person (sub) first, the machine (azp)
     * next, the address last — so one user's burst never counts against
     * another user on the same client. */
    static String subjectKey(ServerWebExchange exchange) {
        String claimed = claimKey(exchange, true);
        return claimed != null ? claimed : ipKey(exchange);
    }

    private static String claimKey(ServerWebExchange exchange, boolean preferSubject) {
        String auth = exchange.getRequest().getHeaders().getFirst("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            String[] parts = auth.substring(7).split("\\.");
            if (parts.length >= 2) {
                try {
                    Map<String, Object> claims = JSON.readValue(
                            Base64.getUrlDecoder().decode(parts[1]), Map.class);
                    if (preferSubject && claims.get("sub") != null) {
                        return "sub:" + claims.get("sub");
                    }
                    Object azp = claims.get("azp") != null ? claims.get("azp") : claims.get("client_id");
                    if (azp != null) {
                        return "client:" + azp;
                    }
                } catch (Exception ignored) {
                    // an unreadable token buckets by address below
                }
            }
        }
        return null;
    }

    private static String ipKey(ServerWebExchange exchange) {
        var remote = exchange.getRequest().getRemoteAddress();
        return "ip:" + (remote == null ? "unknown" : remote.getAddress().getHostAddress());
    }
}
