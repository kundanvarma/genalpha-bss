package com.bss.gateway;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Routing behavior against a mock downstream: path-based dispatch, untouched
 * path and Authorization forwarding, and 404 for paths no service owns.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayRoutingTest {

    static MockWebServer downstream;

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private RouteLocator routeLocator;

    @BeforeAll
    static void startDownstream() throws IOException {
        downstream = new MockWebServer();
        downstream.start();
    }

    @AfterAll
    static void stopDownstream() throws IOException {
        downstream.shutdown();
    }

    @DynamicPropertySource
    static void routeToMock(DynamicPropertyRegistry registry) {
        registry.add("CATALOG_URL", () -> downstream.url("/").toString());
    }

    @Test
    void allFourServiceRoutesAreConfigured() {
        List<String> ids = routeLocator.getRoutes().map(Route::getId).collectList().block();
        assertThat(ids).containsExactlyInAnyOrder(
                "product-catalog", "product-ordering", "product-inventory", "party-account");
    }

    @Test
    void catalogPath_isForwardedWithPathAndAuthorizationIntact() throws Exception {
        downstream.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("[{\"id\":\"po-1\"}]"));

        webTestClient.get()
                .uri("/tmf-api/productCatalogManagement/v4/productOffering?offset=0&limit=10")
                .header("Authorization", "Bearer test-token")
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$[0].id").isEqualTo("po-1");

        RecordedRequest received = downstream.takeRequest(5, TimeUnit.SECONDS);
        assertThat(received).isNotNull();
        assertThat(received.getPath())
                .isEqualTo("/tmf-api/productCatalogManagement/v4/productOffering?offset=0&limit=10");
        assertThat(received.getHeader("Authorization")).isEqualTo("Bearer test-token");
    }

    @Test
    void unknownPath_returns404() {
        webTestClient.get()
                .uri("/tmf-api/noSuchManagement/v4/thing")
                .exchange()
                .expectStatus().isNotFound();
    }
}
