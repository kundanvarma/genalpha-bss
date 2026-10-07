package com.bss.payment;

import com.bss.payment.psp.PspAdapter;
import com.bss.payment.psp.StripePspAdapter;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A payment provider that answers 204, an empty 200, or a proxy's blank page is
 * the quieter sibling of the provider that stalls (see PspTimeoutTest). Spring's
 * RestClient turns a bodyless answer into a null body, and the Stripe adapter
 * read {@code intent.get("status")} straight off it -- while checking
 * {@code intent == null} three lines further down, which made that check dead
 * code and the NPE certain.
 *
 * The cost is not the stack trace. authorize() runs with capture_method=manual,
 * so by the time the body comes back Stripe may already hold an uncaptured
 * payment intent. The customer saw a 500 for money that had moved, and the
 * adapter's own comment three lines below says why an unidentified intent must
 * never read as an approval: capture() would POST the literal "null" and the
 * money would go nowhere behind an approved-looking receipt.
 *
 * On money, "we cannot tell" has to resolve to not-approved. The four sibling
 * adapters -- Vipps, Klarna, MMG, PayPal -- all guard this on the very next
 * line; Stripe was the only one that did not.
 */
class StripeEmptyBodyTest {

    private HttpServer stripe;

    @BeforeEach
    void startAProviderThatSaysNothing() throws IOException {
        stripe = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stripe.createContext("/", exchange -> {
            exchange.sendResponseHeaders(204, -1);   // accepted, and no body
            exchange.close();
        });
        stripe.start();
    }

    @AfterEach
    void stop() {
        stripe.stop(0);
    }

    private StripePspAdapter adapter() {
        return new StripePspAdapter(RestClient.builder(),
                "http://127.0.0.1:" + stripe.getAddress().getPort(), "sk_test_not_a_real_key");
    }

    @Test
    void anEmptyAnswerDeclinesRatherThanThrowing() {
        PspAdapter.Authorization auth = adapter().authorize(
                new BigDecimal("499.00"), "NOK", Map.of("token", "pm_card_visa"), "idem-1");

        assertThat(auth.approved()).isFalse();
        assertThat(auth.requiresAction()).isFalse();
        assertThat(auth.declineReason()).contains("no body");
    }

    @Test
    void anEmptyAnswerNeverReportsACapture() {
        PspAdapter.Capture capture = adapter().capture("pi_123", new BigDecimal("499.00"), "NOK");

        assertThat(capture.settled()).isFalse();
        assertThat(capture.failureReason()).contains("no body");
    }
}
