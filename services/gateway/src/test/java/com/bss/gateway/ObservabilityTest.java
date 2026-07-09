package com.bss.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureObservability
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
