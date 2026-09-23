package com.bss.gateway;

import com.bss.gateway.tenant.TenantHosts;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The browser only ever talks to the gateway, so this is the one place a
 * header can reach a page. Until now no page carried any: the consoles, which
 * still build some DOM with innerHTML, had nothing standing behind that if a
 * customer's name ever arrived with a tag in it, and nothing stopped another
 * site framing a care agent's console to borrow their clicks.
 */
class SecurityHeadersTest {

    /** A registry like the real one: two tenants on one IdP, one on another. */
    private static TenantHosts registry() {
        TenantHosts hosts = new TenantHosts();
        hosts.setRegistry(java.util.List.of(
                entry("http://localhost:8085/realms/bss"),
                entry("http://localhost:8085/realms/taranga"),
                entry("https://id.example.test/realms/nova")));
        return hosts;
    }

    private static TenantHosts.Entry entry(String issuer) {
        TenantHosts.Entry e = new TenantHosts.Entry();
        e.setIssuer(issuer);
        return e;
    }

    private static HttpHeaders headersFor(String path, String forwardedProto) {
        MockServerHttpRequest.BaseBuilder<?> req = MockServerHttpRequest.get(path);
        if (forwardedProto != null) {
            req = req.header("X-Forwarded-Proto", forwardedProto);
        }
        MockServerWebExchange exchange = MockServerWebExchange.from(req);
        new SecurityHeadersFilter(true, registry()).filter(exchange, e -> Mono.empty()).block();
        // headers are written beforeCommit, which is where a proxied response
        // gets them -- so commit the response the way a real answer would
        exchange.getResponse().setComplete().block();
        return exchange.getResponse().getHeaders();
    }

    @Test
    void everyPageCarriesAContentPolicyThatRefusesInjectedScript() {
        HttpHeaders h = headersFor("/shop/", null);

        String csp = h.getFirst("Content-Security-Policy");
        assertThat(csp).contains("script-src 'self'");
        // the innerHTML risk is an injected handler, and 'unsafe-inline' is
        // exactly what would let one run
        assertThat(csp).doesNotContain("'unsafe-inline'; script")
                .doesNotContain("script-src 'self' 'unsafe-inline'");
        assertThat(csp).contains("object-src 'none'")
                .contains("base-uri 'self'")
                .contains("form-action 'self'")
                .contains("frame-ancestors 'self'");
    }

    @Test
    void theAdminConsoleGetsEvalAndStillRefusesInlineScript() {
        String csp = headersFor("/console/index.html", null).getFirst("Content-Security-Policy");

        // its command palette resolves let/const globals from sibling scripts
        assertThat(csp).contains("script-src 'self' 'unsafe-eval'");
        // what matters: eval is not inline. An injected <img onerror> is still refused.
        assertThat(csp).doesNotContain("script-src 'self' 'unsafe-eval' 'unsafe-inline'");
    }

    @Test
    void noOtherPathGetsTheEvalAllowance() {
        assertThat(headersFor("/csr/", null).getFirst("Content-Security-Policy"))
                .doesNotContain("'unsafe-eval'");
        assertThat(headersFor("/tmf-api/productCatalogManagement/v4/productOffering", null)
                .getFirst("Content-Security-Policy")).doesNotContain("'unsafe-eval'");
    }

    @Test
    void theClickjackingAndSniffingHeadersAreOnEveryAnswer() {
        HttpHeaders h = headersFor("/tmf-api/customerBillManagement/v4/customerBill", null);

        assertThat(h.getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(h.getFirst("X-Frame-Options")).isEqualTo("SAMEORIGIN");
        assertThat(h.getFirst("Referrer-Policy")).isEqualTo("no-referrer");
    }

    @Test
    void transportSecurityIsSentOnlyWhenTheRequestArrivedOverTls() {
        // pinning a developer's browser to https://localhost would be a bug
        assertThat(headersFor("/shop/", null).getFirst("Strict-Transport-Security")).isNull();
        assertThat(headersFor("/shop/", "http").getFirst("Strict-Transport-Security")).isNull();

        assertThat(headersFor("/shop/", "https").getFirst("Strict-Transport-Security"))
                .contains("max-age=31536000");
        // a chain of proxies forwards a list; the client's own protocol leads it
        assertThat(headersFor("/shop/", "https, http").getFirst("Strict-Transport-Security"))
                .isNotNull();
        assertThat(headersFor("/shop/", "http, https").getFirst("Strict-Transport-Security"))
                .isNull();
    }

    @Test
    void aComponentThatSetsItsOwnPolicyKeepsIt() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/shop/"));
        exchange.getResponse().getHeaders().set("Content-Security-Policy", "default-src 'none'");

        new SecurityHeadersFilter(true, registry()).filter(exchange, e -> Mono.empty()).block();
        exchange.getResponse().setComplete().block();

        assertThat(exchange.getResponse().getHeaders().getFirst("Content-Security-Policy"))
                .isEqualTo("default-src 'none'");
        // the rest still arrive
        assertThat(exchange.getResponse().getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
    }

    @Test
    void theIdentityProvidersAreReachableOrNobodyCanSignIn() {
        // Every channel signs in against its tenant's issuer and then exchanges
        // the code for a token FROM THE BROWSER. The issuer is another origin,
        // so connect-src 'self' alone does not tighten the page — it stops
        // login outright. This caught exactly that, after the fact.
        String csp = headersFor("/console/", null).getFirst("Content-Security-Policy");

        assertThat(csp).contains("connect-src 'self' http://localhost:8085 https://id.example.test");
        // one entry per origin, not per tenant
        assertThat(csp.split("http://localhost:8085", -1).length - 1).isEqualTo(1);
        // an origin, never a realm path
        assertThat(csp).doesNotContain("/realms/");
    }

    @Test
    void aRegistryWithNoIssuerLeavesConnectSrcAlone() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/shop/"));
        TenantHosts empty = new TenantHosts();
        empty.setRegistry(java.util.List.of());
        new SecurityHeadersFilter(true, empty).filter(exchange, e -> Mono.empty()).block();
        exchange.getResponse().setComplete().block();

        assertThat(exchange.getResponse().getHeaders().getFirst("Content-Security-Policy"))
                .contains("connect-src 'self';");
    }

    @Test
    void theWholeThingHasAnOffSwitch() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/shop/"));
        new SecurityHeadersFilter(false, registry()).filter(exchange, e -> Mono.empty()).block();
        exchange.getResponse().setComplete().block();

        assertThat(exchange.getResponse().getHeaders().getFirst("Content-Security-Policy")).isNull();
    }
}
