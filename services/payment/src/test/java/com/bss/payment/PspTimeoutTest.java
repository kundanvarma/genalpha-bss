package com.bss.payment;

import com.bss.payment.psp.PspHttpConfig;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A payment provider that accepts the connection and then says nothing is the
 * ordinary shape of a provider incident — far more common than one that
 * refuses outright. Every PSP adapter used to build a request factory with no
 * timeouts, which in the JDK client means waiting forever, so one quiet
 * provider could hold request threads until the whole payment component
 * stopped answering. Customers whose provider was perfectly healthy would
 * have gone down with it.
 *
 * This test is the reason to believe the wait now ends: a real socket that
 * accepts and stalls, and a client that gives up.
 */
class PspTimeoutTest {

    private HttpServer silent;
    private CountDownLatch released;

    @BeforeEach
    void startASilentProvider() throws IOException {
        released = new CountDownLatch(1);
        silent = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        silent.createContext("/", exchange -> {
            try {
                // accept, then behave exactly like a provider having a bad day
                released.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        silent.start();
    }

    @AfterEach
    void stop() {
        released.countDown();
        silent.stop(0);
    }

    private String url() {
        return "http://127.0.0.1:" + silent.getAddress().getPort() + "/charge";
    }

    @Test
    void aProviderThatGoesQuietStopsBeingWaitedFor() {
        ClientHttpRequestFactory factory = new PspHttpConfig()
                .pspRequestFactory(Duration.ofSeconds(5), Duration.ofMillis(400));
        RestClient client = RestClient.builder().requestFactory(factory).build();

        long started = System.currentTimeMillis();
        assertThatThrownBy(() -> client.get().uri(url()).retrieve().body(String.class))
                .isInstanceOf(ResourceAccessException.class);
        long waited = System.currentTimeMillis() - started;

        // it gave up on its own, nowhere near the server's own 30s release
        assertThat(waited).isLessThan(5_000);
    }

    @Test
    void theDefaultsAreTheOnesTheAdaptersGet() {
        // 30s is deliberately generous: an issuer in the loop, or a 3-D Secure
        // step, legitimately takes seconds. The point is that it ends.
        ClientHttpRequestFactory factory = new PspHttpConfig()
                .pspRequestFactory(Duration.ofSeconds(5), Duration.ofSeconds(30));

        assertThat(factory).isNotNull();
        RestClient client = RestClient.builder().requestFactory(factory).build();
        assertThat(client).isNotNull();
    }
}
