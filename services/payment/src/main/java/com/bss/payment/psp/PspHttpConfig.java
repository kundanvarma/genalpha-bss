package com.bss.payment.psp;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * How long a payment provider may keep us waiting.
 *
 * Every PSP adapter built its own request factory with no timeouts at all,
 * which in the JDK client means waiting forever. A provider that accepts the
 * connection and then goes quiet — the ordinary shape of a provider incident,
 * far more common than one that refuses outright — holds the request thread
 * until it is killed. Enough of those and the payment component stops
 * answering anyone, including the customers whose provider is perfectly
 * healthy. One slow vendor becomes our outage.
 *
 * CLAUDE.md already says a quiet vendor never blocks the customer's action.
 * That is only true if the wait ends.
 *
 * The read timeout is generous on purpose. A card authorization with an
 * issuer in the loop, or a 3-D Secure step, legitimately takes seconds; the
 * number here is the point past which waiting has stopped being useful, not
 * a service-level target. Connecting is different: a TCP handshake that has
 * not completed in a few seconds is not going to.
 */
@Configuration
public class PspHttpConfig {

    @Bean
    public ClientHttpRequestFactory pspRequestFactory(
            @Value("${bss.payment.psp.connect-timeout:5s}") Duration connectTimeout,
            @Value("${bss.payment.psp.read-timeout:30s}") Duration readTimeout) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(connectTimeout).build());
        factory.setReadTimeout(readTimeout);
        return factory;
    }
}
