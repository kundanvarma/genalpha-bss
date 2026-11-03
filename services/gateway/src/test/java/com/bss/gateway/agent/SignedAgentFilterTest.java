package com.bss.gateway.agent;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Signed agents at the edge (#427): a request signed by a trusted agent operator
 * (Web Bot Auth, RFC 9421, Ed25519) reaches downstream with a gateway-stamped
 * {@code X-Verified-Agent}; a forged, expired, tampered or unknown signature is
 * treated as anonymous; and no client can set {@code X-Verified-Agent} itself.
 * Every signature here is real, made in the test with a key pair generated in
 * the test.
 */
class SignedAgentFilterTest {

    private static final long NOW = 1_800_000_000L;
    private static final String AGENT_URL = "https://agents.example.test";
    private static final String DIRECTORY = AGENT_URL + "/.well-known/http-message-signatures-directory";

    private final TestAgentSigner agent = new TestAgentSigner(AGENT_URL);
    private final Clock clock = Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC);

    private WebBotAuthProperties staticKeyConfig(TestAgentSigner signer) {
        WebBotAuthProperties p = new WebBotAuthProperties();
        WebBotAuthProperties.Operator op = new WebBotAuthProperties.Operator();
        op.setName("example-agents");
        op.setSignatureAgent(AGENT_URL);
        WebBotAuthProperties.Jwk jwk = new WebBotAuthProperties.Jwk();
        jwk.setKty("OKP");
        jwk.setCrv("Ed25519");
        jwk.setX(signer.x);
        op.setKeys(List.of(jwk));
        p.setOperators(List.of(op));
        return p;
    }

    private SignedAgentFilter filter(WebBotAuthProperties p, AgentKeyDirectory.Fetcher fetcher) {
        return new SignedAgentFilter(new WebBotAuthVerifier(new AgentKeyDirectory(p, fetcher, clock), p, clock), null);
    }

    private static final AgentKeyDirectory.Fetcher NO_FETCH = url -> {
        throw new AssertionError("must not fetch " + url);
    };

    /** Runs the filter and returns the headers downstream would have seen. */
    private static HttpHeaders downstream(SignedAgentFilter f, MockServerHttpRequest request) {
        AtomicReference<HttpHeaders> seen = new AtomicReference<>();
        f.filter(MockServerWebExchange.from(request), ex -> {
            seen.set(ex.getRequest().getHeaders());
            return Mono.empty();
        }).block(Duration.ofSeconds(5));
        assertThat(seen.get()).as("the request always continues — never a 500, never a refusal").isNotNull();
        return seen.get();
    }

    private static MockServerHttpRequest signed(String host, TestAgentSigner.Headers s, String... extra) {
        MockServerHttpRequest.BaseBuilder<?> b = MockServerHttpRequest.get("http://" + host + "/acp/product_feed")
                .header(HttpHeaders.HOST, host)
                .header("Signature-Input", s.signatureInput())
                .header("Signature", s.signature())
                .header("Signature-Agent", s.signatureAgent());
        for (int i = 0; i + 1 < extra.length; i += 2) {
            b = b.header(extra[i], extra[i + 1]);
        }
        return b.build();
    }

    @Test
    void aTrustedSignatureStampsTheVerifiedAgent_andTheSelfDeclaredOneIsDropped() {
        HttpHeaders h = downstream(filter(staticKeyConfig(agent), NO_FETCH),
                signed("shop.example.test", agent.sign("shop.example.test", NOW - 10, NOW + 300),
                        "X-GenAlpha-Agent", "care-assist", "X-Verified-Agent", "someone-else"));

        assertThat(h.get("X-Verified-Agent")).containsExactly("example-agents");
        assertThat(h.getFirst("X-Verified-Agent-Key")).isEqualTo(agent.keyid);
        assertThat(h.getFirst("X-GenAlpha-Agent")).isNull();
    }

    @Test
    void aSignatureForAnotherHostIsAnonymous() {
        // signed for shop.example.test, replayed against another host
        HttpHeaders h = downstream(filter(staticKeyConfig(agent), NO_FETCH),
                signed("console.example.test", agent.sign("shop.example.test", NOW - 10, NOW + 300),
                        "X-GenAlpha-Agent", "care-assist"));

        assertThat(h.getFirst("X-Verified-Agent")).isNull();
        assertThat(h.getFirst("X-Verified-Agent-Key")).isNull();
        assertThat(h.getFirst("X-GenAlpha-Agent")).as("a broken signature earns not even the declaration").isNull();
    }

    @Test
    void aTamperedSignatureIsAnonymous() {
        TestAgentSigner.Headers good = agent.sign("shop.example.test", NOW - 10, NOW + 300);
        // stretch the window after signing: the parameters are covered, so the signature breaks
        TestAgentSigner.Headers stretched = new TestAgentSigner.Headers(
                good.signatureInput().replace("expires=" + (NOW + 300), "expires=" + (NOW + 3000)),
                good.signature(), good.signatureAgent());

        HttpHeaders h = downstream(filter(staticKeyConfig(agent), NO_FETCH), signed("shop.example.test", stretched));

        assertThat(h.getFirst("X-Verified-Agent")).isNull();
    }

    @Test
    void anExpiredOrPostDatedOrOverlongSignatureIsAnonymous() {
        SignedAgentFilter f = filter(staticKeyConfig(agent), NO_FETCH);
        assertThat(downstream(f, signed("shop.example.test", agent.sign("shop.example.test", NOW - 900, NOW - 300)))
                .getFirst("X-Verified-Agent")).as("expired").isNull();
        assertThat(downstream(f, signed("shop.example.test", agent.sign("shop.example.test", NOW + 600, NOW + 900)))
                .getFirst("X-Verified-Agent")).as("not yet valid").isNull();
        assertThat(downstream(f, signed("shop.example.test", agent.sign("shop.example.test", NOW - 10, NOW + 2 * 86_400)))
                .getFirst("X-Verified-Agent")).as("longer than the 24 h the draft recommends").isNull();
    }

    @Test
    void aKeyNoConfiguredOperatorHoldsIsAnonymous() {
        TestAgentSigner stranger = new TestAgentSigner(AGENT_URL);
        HttpHeaders h = downstream(filter(staticKeyConfig(agent), NO_FETCH),
                signed("shop.example.test", stranger.sign("shop.example.test", NOW - 10, NOW + 300)));

        assertThat(h.getFirst("X-Verified-Agent")).isNull();
    }

    @Test
    void aSpoofedVerifiedAgentHeaderIsStripped_andTheDeclarationStays() {
        MockServerHttpRequest req = MockServerHttpRequest.get("http://shop.example.test/tmf-api/x")
                .header("X-Verified-Agent", "example-agents")
                .header("X-Verified-Agent-Key", agent.keyid)
                .header("X-GenAlpha-Agent", "care-assist")
                .build();

        HttpHeaders h = downstream(filter(staticKeyConfig(agent), NO_FETCH), req);

        assertThat(h.getFirst("X-Verified-Agent")).isNull();
        assertThat(h.getFirst("X-Verified-Agent-Key")).isNull();
        assertThat(h.getFirst("X-GenAlpha-Agent")).as("still the agent's own word, as before").isEqualTo("care-assist");
    }

    @Test
    void anUncoveredSignatureAgentCannotBeAttributed() {
        TestAgentSigner.Headers s = agent.sign("shop.example.test", NOW - 10, NOW + 300);
        // the same signature, but the input no longer lists signature-agent: a verifier must
        // not attribute it to a member it does not cover. Re-sign so only coverage differs.
        String params = "(\"@authority\");created=" + (NOW - 10) + ";expires=" + (NOW + 300)
                + ";keyid=\"" + agent.keyid + "\";tag=\"web-bot-auth\"";
        String sig = agent.sign("\"@authority\": shop.example.test\n\"@signature-params\": " + params);
        HttpHeaders h = downstream(filter(staticKeyConfig(agent), NO_FETCH), signed("shop.example.test",
                new TestAgentSigner.Headers("sig1=" + params, "sig1=:" + sig + ":", s.signatureAgent())));

        assertThat(h.getFirst("X-Verified-Agent")).isNull();
    }

    @Test
    void aSignatureAgentUrlNobodyConfiguredIsNeverFetched() {
        TestAgentSigner elsewhere = new TestAgentSigner("https://attacker.example.test");
        WebBotAuthProperties p = staticKeyConfig(agent);
        p.getOperators().get(0).setDirectory(DIRECTORY);
        List<String> fetched = new ArrayList<>();

        HttpHeaders h = downstream(filter(p, url -> {
            fetched.add(url);
            return Mono.just("{\"keys\":[]}");
        }), signed("shop.example.test", elsewhere.sign("shop.example.test", NOW - 10, NOW + 300)));

        assertThat(h.getFirst("X-Verified-Agent")).isNull();
        assertThat(fetched).as("only configured directories are ever read").isEmpty();
    }

    @Test
    void directoryKeysAreFetchedFromTheConfiguredUrl_cached_andRefetchedNoFasterThanTheFloor() {
        WebBotAuthProperties p = new WebBotAuthProperties();
        WebBotAuthProperties.Operator op = new WebBotAuthProperties.Operator();
        op.setName("example-agents");
        op.setDirectory(DIRECTORY);
        p.setOperators(List.of(op));
        AtomicInteger fetches = new AtomicInteger();
        List<String> urls = new ArrayList<>();
        SignedAgentFilter f = filter(p, url -> {
            fetches.incrementAndGet();
            urls.add(url);
            return Mono.just("{\"keys\":[{\"kty\":\"OKP\",\"crv\":\"Ed25519\",\"x\":\"" + agent.x + "\"}]}");
        });

        HttpHeaders first = downstream(f, signed("shop.example.test", agent.sign("shop.example.test", NOW - 10, NOW + 300)));
        HttpHeaders second = downstream(f, signed("shop.example.test", agent.sign("shop.example.test", NOW - 5, NOW + 300)));
        assertThat(first.getFirst("X-Verified-Agent")).isEqualTo("example-agents");
        assertThat(second.getFirst("X-Verified-Agent")).isEqualTo("example-agents");
        assertThat(urls).containsOnly(DIRECTORY);
        assertThat(fetches).as("the second request is answered from the cache").hasValue(1);

        // a flood of made-up key ids cannot turn the gateway into a fetch amplifier
        for (int i = 0; i < 20; i++) {
            TestAgentSigner junk = new TestAgentSigner(AGENT_URL);
            assertThat(downstream(f, signed("shop.example.test", junk.sign("shop.example.test", NOW - 10, NOW + 300)))
                    .getFirst("X-Verified-Agent")).isNull();
        }
        assertThat(fetches).hasValue(1);
    }

    @Test
    void anUnreachableDirectoryMeansAnonymous_notAnError() {
        WebBotAuthProperties p = new WebBotAuthProperties();
        WebBotAuthProperties.Operator op = new WebBotAuthProperties.Operator();
        op.setName("example-agents");
        op.setDirectory(DIRECTORY);
        p.setOperators(List.of(op));

        HttpHeaders h = downstream(filter(p, url -> Mono.error(new java.io.IOException("connection refused"))),
                signed("shop.example.test", agent.sign("shop.example.test", NOW - 10, NOW + 300)));

        assertThat(h.getFirst("X-Verified-Agent")).isNull();
    }

    @Test
    void garbageSignatureHeadersAreAnonymous() {
        MockServerHttpRequest req = MockServerHttpRequest.get("http://shop.example.test/x")
                .header("Signature-Input", "sig1=(\"@authority\";tag=\"web-bot-auth\"")
                .header("Signature", "sig1=:%%%:")
                .header("X-GenAlpha-Agent", "care-assist")
                .build();

        HttpHeaders h = downstream(filter(staticKeyConfig(agent), NO_FETCH), req);

        assertThat(h.getFirst("X-Verified-Agent")).isNull();
        assertThat(h.getFirst("X-GenAlpha-Agent")).isNull();
    }

    @Test
    void anHttpDirectoryIsRefusedUnlessExplicitlyAllowed() {
        WebBotAuthProperties p = new WebBotAuthProperties();
        WebBotAuthProperties.Operator op = new WebBotAuthProperties.Operator();
        op.setName("example-agents");
        op.setSignatureAgent(AGENT_URL);
        op.setDirectory("http://agents.example.test/.well-known/http-message-signatures-directory");
        p.setOperators(List.of(op));
        AtomicInteger fetches = new AtomicInteger();
        AgentKeyDirectory.Fetcher fetcher = url -> {
            fetches.incrementAndGet();
            return Mono.just("{\"keys\":[{\"kty\":\"OKP\",\"crv\":\"Ed25519\",\"x\":\"" + agent.x + "\"}]}");
        };

        HttpHeaders refused = downstream(filter(p, fetcher),
                signed("shop.example.test", agent.sign("shop.example.test", NOW - 10, NOW + 300)));
        assertThat(fetches).hasValue(0);
        assertThat(refused.getFirst("X-Verified-Agent")).isNull();

        p.setAllowInsecureDirectories(true); // a laptop stand-in, said out loud
        HttpHeaders allowed = downstream(filter(p, fetcher),
                signed("shop.example.test", agent.sign("shop.example.test", NOW - 10, NOW + 300)));
        assertThat(fetches).hasValue(1);
        assertThat(allowed.getFirst("X-Verified-Agent")).isEqualTo("example-agents");
    }
}
