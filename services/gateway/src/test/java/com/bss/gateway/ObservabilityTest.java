package com.bss.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

// Boot 4 removes this annotation — not moved, gone. Without it a
// @SpringBootTest disables metrics export, so /actuator/prometheus answers with
// nothing and this test would pass while proving the opposite of what it claims.
//
// `spring.test.observability.auto-configure` is the same switch as a property,
// and it exists in BOTH 3.5.16 and 4.0.8 — so this lands today, on the version
// we run, and survives the migration (#199) instead of blocking it.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.test.observability.auto-configure=true")
class ObservabilityTest {

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void prometheusEndpoint_isOpenAndServesMetrics() {
        byte[] body = webTestClient.get().uri("/actuator/prometheus")
                .exchange()
                .expectStatus().isOk()
                .expectBody().returnResult().getResponseBody();
        String metrics = new String(body);
        assertThat(metrics).contains("jvm_memory_used_bytes");
        assertThat(metrics).contains("application=\"gateway\"");
    }
}
