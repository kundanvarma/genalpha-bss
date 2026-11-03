package com.bss.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A caller must not choose the trace id the platform files its evidence under
 * (review finding 16). The ontology's receipts and decisions and intelligence's
 * AI ledger store the trace id as the key that joins them; a {@code traceparent}
 * passed through from outside let a caller pick that key — pointing two
 * customers' receipts at one trace — and a sampled flag of {@code 00} switched
 * the export off. The gateway is the edge: whatever trace context arrives from
 * outside is dropped, and the first traced component starts its own.
 */
class TraceHeaderFilterTest {

    private static HttpHeaders forwarded(MockServerHttpRequest.BaseBuilder<?> request) {
        AtomicReference<HttpHeaders> seen = new AtomicReference<>();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        new TraceHeaderFilter().filter(exchange, e -> {
            seen.set(e.getRequest().getHeaders());
            return Mono.empty();
        }).block();
        return seen.get();
    }

    @Test
    void anInboundTraceContextNeverReachesAComponent() {
        HttpHeaders h = forwarded(MockServerHttpRequest.post("/ontology/v1/actions/issueCredit/execute")
                .header("traceparent", "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-00")
                .header("tracestate", "vendor=chosen-by-the-caller")
                .header("baggage", "tenant=nova")
                .header("b3", "0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-0")
                .header("X-B3-TraceId", "0af7651916cd43dd8448eb211c80319c")
                .header("X-B3-SpanId", "b7ad6b7169203331")
                .header("X-B3-Sampled", "0")
                .header("Authorization", "Bearer t")
                .header("X-Channel", "care"));
        assertThat(h.containsHeader("traceparent")).isFalse();
        assertThat(h.containsHeader("tracestate")).isFalse();
        assertThat(h.containsHeader("baggage")).isFalse();
        assertThat(h.containsHeader("b3")).isFalse();
        assertThat(h.containsHeader("X-B3-TraceId")).isFalse();
        assertThat(h.containsHeader("X-B3-SpanId")).isFalse();
        assertThat(h.containsHeader("X-B3-Sampled")).isFalse();
        // nothing else is touched
        assertThat(h.getFirst("Authorization")).isEqualTo("Bearer t");
        assertThat(h.getFirst("X-Channel")).isEqualTo("care");
    }

    @Test
    void theHeaderNamesAreMatchedWhateverTheirCase() {
        HttpHeaders h = forwarded(MockServerHttpRequest.get("/shop/")
                .header("TraceParent", "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01"));
        assertThat(h.containsHeader("traceparent")).isFalse();
    }
}
