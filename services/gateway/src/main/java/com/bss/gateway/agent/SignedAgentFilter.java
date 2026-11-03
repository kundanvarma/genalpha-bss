package com.bss.gateway.agent;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;

/**
 * Signed agents at the edge (#427). An AI agent that signs its requests with
 * Web Bot Auth (RFC 9421 HTTP Message Signatures, Ed25519, tag {@code web-bot-auth})
 * is checked here against the keys of the agent operators this deployment trusts.
 *
 * <ul>
 *   <li><b>Verified</b> — the client's {@code X-GenAlpha-Agent} and any
 *       {@code X-Verified-Agent} are removed and the gateway stamps
 *       {@code X-Verified-Agent: <operator>} and {@code X-Verified-Agent-Key: <keyid>}.</li>
 *   <li><b>Refused</b> (bad, expired, unknown key, malformed) — all three agent
 *       headers are removed; the request goes on as an ordinary anonymous-agent
 *       request. Never a 500, never a refusal: a broken signature proves nothing,
 *       so it earns nothing, and the person's own token still decides access.</li>
 *   <li><b>Absent</b> — any client-sent {@code X-Verified-Agent(-Key)} is removed;
 *       {@code X-GenAlpha-Agent} passes as the agent's own declaration, as before.</li>
 * </ul>
 *
 * Downstream may trust {@code X-Verified-Agent} because no client can set it: it
 * is removed from every request that crosses the gateway and written only here.
 */
@Component
public class SignedAgentFilter implements GlobalFilter, Ordered {

    public static final String VERIFIED_AGENT = "X-Verified-Agent";
    public static final String VERIFIED_AGENT_KEY = "X-Verified-Agent-Key";
    public static final String DECLARED_AGENT = "X-GenAlpha-Agent";

    private static final Logger log = LoggerFactory.getLogger(SignedAgentFilter.class);

    private final WebBotAuthVerifier verifier;
    private final MeterRegistry meters;

    @Autowired
    public SignedAgentFilter(WebBotAuthProperties props, ObjectProvider<MeterRegistry> meters) {
        this(new WebBotAuthVerifier(new AgentKeyDirectory(props, webClientFetcher(), Clock.systemUTC()),
                props, Clock.systemUTC()), meters.getIfAvailable());
    }

    SignedAgentFilter(WebBotAuthVerifier verifier, MeterRegistry meters) {
        this.verifier = verifier;
        this.meters = meters;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return verifier.verify(exchange.getRequest())
                .onErrorResume(e -> {
                    log.warn("web-bot-auth: verification failed unexpectedly: {}", e.toString());
                    return Mono.just(new WebBotAuthVerifier.Refused(WebBotAuthVerifier.Reason.MALFORMED));
                })
                .defaultIfEmpty(new WebBotAuthVerifier.Refused(WebBotAuthVerifier.Reason.MALFORMED))
                .flatMap(outcome -> {
                    count(outcome);
                    ServerHttpRequest request = exchange.getRequest().mutate()
                            .headers(h -> {
                                h.remove(VERIFIED_AGENT);
                                h.remove(VERIFIED_AGENT_KEY);
                                switch (outcome) {
                                    case WebBotAuthVerifier.Verified v -> {
                                        h.remove(DECLARED_AGENT);
                                        h.set(VERIFIED_AGENT, v.operator());
                                        h.set(VERIFIED_AGENT_KEY, v.keyid());
                                    }
                                    case WebBotAuthVerifier.Refused r -> h.remove(DECLARED_AGENT);
                                    case WebBotAuthVerifier.Absent a -> {
                                    }
                                }
                            })
                            .build();
                    return chain.filter(exchange.mutate().request(request).build());
                });
    }

    private void count(WebBotAuthVerifier.Outcome outcome) {
        if (outcome instanceof WebBotAuthVerifier.Refused r) {
            log.debug("web-bot-auth: signature refused ({}); request treated as anonymous", r.reason());
        }
        if (meters == null || outcome instanceof WebBotAuthVerifier.Absent) {
            return;
        }
        String result = outcome instanceof WebBotAuthVerifier.Verified ? "verified" : "refused";
        String reason = outcome instanceof WebBotAuthVerifier.Refused r ? r.reason().name().toLowerCase(java.util.Locale.ROOT) : "none";
        meters.counter("gateway.web_bot_auth", "outcome", result, "reason", reason).increment();
    }

    @Override
    public int getOrder() {
        // after TenantHostFilter (HIGHEST_PRECEDENCE), before the agent-commerce
        // gate (-60) and the rate limiter (-50), so both see the verified agent
        return -80;
    }

    private static AgentKeyDirectory.Fetcher webClientFetcher() {
        WebClient client = WebClient.builder()
                .codecs(c -> c.defaultCodecs().maxInMemorySize(64 * 1024))
                .build();
        return url -> client.get().uri(url)
                .accept(MediaType.parseMediaType("application/http-message-signatures-directory+json"),
                        MediaType.APPLICATION_JSON)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(3));
    }
}
