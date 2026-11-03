package com.bss.gateway;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * The platform starts its own traces; a caller does not choose them (review finding 16).
 *
 * <p>The ontology's receipts and decisions and intelligence's AI ledger store the
 * W3C trace id as the key that joins a trace, a receipt and a decision. Passed
 * through unchanged, an inbound {@code traceparent} let any caller pick that key —
 * filing its actions under someone else's trace — and a sampled flag of {@code 00}
 * switched the export of the platform's own spans off. Everything that reaches the
 * gateway comes from outside (browsers, apps, partners, agents); components call
 * each other directly, not through it. So inbound trace context is dropped here, in
 * every propagation format Micrometer Tracing can be configured to read, and the
 * first traced component behind the gateway starts a fresh trace.
 *
 * <p>Honest limit: a component that does reach a sibling through the gateway starts
 * a new trace at that hop. None of the traced ones (ontology, intelligence) do.
 */
@Component
public class TraceHeaderFilter implements GlobalFilter, Ordered {

    /** W3C Trace Context and Baggage, and both B3 spellings. Header names match case-insensitively. */
    static final List<String> TRACE_HEADERS = List.of(
            "traceparent", "tracestate", "baggage",
            "b3", "X-B3-TraceId", "X-B3-SpanId", "X-B3-ParentSpanId", "X-B3-Sampled", "X-B3-Flags");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(h -> TRACE_HEADERS.forEach(h::remove))
                .build();
        return chain.filter(exchange.mutate().request(request).build());
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
