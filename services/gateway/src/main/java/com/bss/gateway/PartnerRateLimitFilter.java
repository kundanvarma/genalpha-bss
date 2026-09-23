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
    private final int probeCapacity;
    private final long probeWindowMs;
    /**
     * Anonymous paths that answer a question about the operator's own data and
     * can therefore be walked.
     *
     * The number offer is the one that prompted this: a shopper must see free
     * numbers before signing in, so it cannot be closed — but every draw also
     * says which candidates are NOT free, and a caller varying the shuffle can
     * map an operator's issued MSISDNs a few hundred at a time. The wide ring
     * covers it already at a fleet-wide ceiling, which is generous enough that
     * a patient walker never notices it. This ring is sized for a human
     * pressing shuffle, not for a program.
     */
    private final java.util.Set<String> probePaths;
    private final com.bss.gateway.ratelimit.RateLimitStore store;

    public PartnerRateLimitFilter(
            @Value("${bss.gateway.partner-rate.capacity:60}") int capacity,
            @Value("${bss.gateway.partner-rate.window-ms:60000}") long windowMs,
            @Value("${bss.gateway.global-rate.capacity:1200}") int globalCapacity,
            @Value("${bss.gateway.global-rate.window-ms:60000}") long globalWindowMs,
            @Value("${bss.gateway.probe-rate.capacity:30}") int probeCapacity,
            @Value("${bss.gateway.probe-rate.window-ms:60000}") long probeWindowMs,
            @Value("${bss.gateway.probe-rate.paths:/tmf-api/resourcePoolManagement/v4/numberOffer}")
                    String probePaths,
            @Value("${bss.gateway.redis-url:}") String redisUrl) {
        this.capacity = capacity;
        this.windowMs = windowMs;
        this.globalCapacity = globalCapacity;
        this.globalWindowMs = globalWindowMs;
        this.probeCapacity = probeCapacity;
        this.probeWindowMs = probeWindowMs;
        this.probePaths = java.util.Arrays.stream(probePaths.split(","))
                .map(String::trim).filter(p -> !p.isEmpty())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        // the seam: one gateway keeps its buckets in memory; replicas (or
        // anyone who wants restart-surviving windows) point REDIS_URL at
        // a shared store and every replica enforces the SAME ceiling
        if (redisUrl == null || redisUrl.isBlank()) {
            this.store = new com.bss.gateway.ratelimit.InMemoryRateLimitStore();
        } else {
            this.store = new com.bss.gateway.ratelimit.RedisRateLimitStore(redisUrl);
            log.info("rate-limit buckets in Redis at {} — shared across replicas", redisUrl);
        }
    }

    @Override
    public int getOrder() {
        return -50; // before routing — a refused knock costs no backend call
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        boolean dealerSurface = path.startsWith("/dealer/v1/");
        boolean probeSurface = probePaths.contains(path);
        // the strict ring for partners, a tight ring for walkable anonymous
        // lookups, the wide ring for everyone else
        String key = dealerSurface ? partnerKey(exchange)
                : (probeSurface ? "p:" + path + ":" + subjectKey(exchange) : "g:" + subjectKey(exchange));
        long retryAfterSeconds;
        if (dealerSurface) {
            retryAfterSeconds = tryAcquire(key, capacity, windowMs);
        } else if (probeSurface) {
            retryAfterSeconds = tryAcquire(key, probeCapacity, probeWindowMs);
        } else {
            retryAfterSeconds = tryAcquire(key, globalCapacity, globalWindowMs);
        }
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
    long tryAcquire(String key, int bucketCapacity, long bucketWindowMs) {
        return store.tryAcquire(key, bucketCapacity, bucketWindowMs);
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
