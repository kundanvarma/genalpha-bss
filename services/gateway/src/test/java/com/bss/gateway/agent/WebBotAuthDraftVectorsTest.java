package com.bss.gateway.agent;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The published Ed25519 test vectors of draft-ietf-webbotauth-httpsig-protocol-01
 * (Appendix E.2.1 dictionary form, E.2.2 legacy string form), signed by the
 * RFC 9421 Appendix B.1.4 test key. Only the PUBLIC half of that key is here.
 * If these pass, the gateway's signature base and parsing agree with the draft's
 * authors, not only with our own test signer.
 */
class WebBotAuthDraftVectorsTest {

    /** RFC 9421 B.1.4 test-key-ed25519, public part. */
    private static final String X = "JrQLj5P_89iXES9-vFgrIy29clF9CC_oPPsw3c5D0bs";
    private static final String KEYID = "poqkLGiymh_W0uP6PZFw-dvez3QJT5SolqXBCW38r0U";

    private WebBotAuthVerifier verifier(Duration maxValidity) {
        WebBotAuthProperties p = new WebBotAuthProperties();
        p.setMaxValidity(maxValidity);
        WebBotAuthProperties.Operator op = new WebBotAuthProperties.Operator();
        op.setName("signature-agent-test");
        op.setSignatureAgent("https://signature-agent.test");
        WebBotAuthProperties.Jwk jwk = new WebBotAuthProperties.Jwk();
        jwk.setKty("OKP");
        jwk.setCrv("Ed25519");
        jwk.setX(X);
        op.setKeys(List.of(jwk));
        p.setOperators(List.of(op));
        Clock clock = Clock.fixed(Instant.ofEpochSecond(1735689700L), ZoneOffset.UTC);
        return new WebBotAuthVerifier(new AgentKeyDirectory(p, url -> Mono.error(new AssertionError()), clock), p, clock);
    }

    @Test
    void theKeyIdIsTheJwkThumbprint() {
        assertThat(Ed25519Keys.thumbprint(X)).isEqualTo(KEYID);
    }

    @Test
    void appendixE21_dictionarySignatureAgent() {
        MockServerHttpRequest req = MockServerHttpRequest.get("http://example.com/foo?param=Value&Pet=dog")
                .header(HttpHeaders.HOST, "example.com")
                .header("Signature-Agent", "sig2=\"https://signature-agent.test\"")
                .header("Signature-Input", "sig2=(\"@authority\" \"signature-agent\";key=\"sig2\")"
                        + ";created=1735689600;keyid=\"" + KEYID + "\";alg=\"ed25519\";expires=4889289600"
                        + ";nonce=\"n9p433xm+NJ3ph3upfBIGmsuwHw387YV7Q/F+6BSpGCVjYCqQw6rznNA8PVVLySrAWsv0hQtFioQb6E1YsauiA==\""
                        + ";tag=\"web-bot-auth\"")
                .header("Signature", "sig2=:CGokAoY/FrPpyuzTe1Fb2kLTAH9Idw6qSFd1+D77r1firsMRCxeuLu5e6O+ek3zQgDMAzjiXtLVfqE00xnABCQ==:")
                .build();

        // the vector's window is a century long; only for the vector is the 24 h ceiling lifted
        assertThat(verifier(Duration.ofDays(365 * 200)).verify(req).block())
                .isEqualTo(new WebBotAuthVerifier.Verified("signature-agent-test", KEYID));
        assertThat(verifier(Duration.ofHours(24)).verify(req).block())
                .isEqualTo(new WebBotAuthVerifier.Refused(WebBotAuthVerifier.Reason.WINDOW_TOO_LONG));
    }

    @Test
    void appendixE22_legacyStringSignatureAgent() {
        MockServerHttpRequest req = MockServerHttpRequest.get("http://example.com/foo")
                .header(HttpHeaders.HOST, "example.com")
                .header("Signature-Agent", "\"https://signature-agent.test\"")
                .header("Signature-Input", "sig2=(\"@authority\" \"signature-agent\")"
                        + ";created=1735689600;keyid=\"" + KEYID + "\";alg=\"ed25519\";expires=1735693200"
                        + ";nonce=\"e8N7S2MFd/qrd6T2R3tdfAuuANngKI7LFtKYI/vowzk4lAZYadIX6wW25MwG7DCT9RUKAJ0qVkU0mEeLElW1qg==\""
                        + ";tag=\"web-bot-auth\"")
                .header("Signature", "sig2=:jdq0SqOwHdyHr9+r5jw3iYZH6aNGKijYp/EstF4RQTQdi5N5YYKrD+mCT1HA1nZDsi6nJKuHxUi/5Syp3rLWBA==:")
                .build();

        assertThat(verifier(Duration.ofHours(24)).verify(req).block())
                .isEqualTo(new WebBotAuthVerifier.Verified("signature-agent-test", KEYID));
    }
}
