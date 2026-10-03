package com.bss.gateway;

import com.bss.gateway.tenant.CrawlerVisibilityFilter;
import com.bss.gateway.tenant.TenantHosts;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A `dark` tenant's Disallow is a request not to FETCH. It is not a request not
 * to LIST: a search engine that finds the URL on someone else's page can index
 * the bare URL precisely because it obeyed the Disallow and never learned there
 * was nothing there. The only thing that closes that is noindex, and the only
 * place it can be stamped per tenant is the gateway — the storefront's nginx
 * serves one static build and knows nothing about tenants.
 */
class CrawlerVisibilityTest {

    private static TenantHosts registry() {
        TenantHosts hosts = new TenantHosts();
        hosts.setDefaultTenant("genalpha");
        hosts.setRegistry(List.of(
                entry("genalpha", "open", "localhost"),
                entry("nova", "search-only", "shop.nova.localhost"),
                entry("nordlys", "search-ai", "shop.nordlys.localhost"),
                entry("fjord", "dark", "shop.fjord.localhost")));
        return hosts;
    }

    private static TenantHosts.Entry entry(String id, String visibility, String host) {
        TenantHosts.Entry e = new TenantHosts.Entry();
        e.setId(id);
        e.setAiVisibility(visibility);
        e.setHosts(List.of(host));
        return e;
    }

    private static String robotsTagFor(String url) {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get(url));
        new CrawlerVisibilityFilter(true, registry()).filter(exchange, e -> Mono.empty()).block();
        // the header is written beforeCommit, where a proxied answer gets it
        exchange.getResponse().setComplete().block();
        return exchange.getResponse().getHeaders().getFirst(CrawlerVisibilityFilter.HEADER);
    }

    @Test
    void aDarkTenantsPublicPagesSayNoindex() {
        assertThat(robotsTagFor("http://shop.fjord.localhost:8080/shop/"))
                .isEqualTo("noindex, nofollow");
        // every surface, not just the shop: a dark tenant wants none of it listed
        assertThat(robotsTagFor("http://shop.fjord.localhost:8080/robots.txt"))
                .isEqualTo("noindex, nofollow");
        assertThat(robotsTagFor("http://shop.fjord.localhost:8080/tmf-api/productCatalogManagement/v5/productOffering"))
                .isEqualTo("noindex, nofollow");
    }

    @Test
    void everyOtherStateIsLeftIndexable() {
        // a search-only tenant WANTS classic search; noindex would delist it
        assertThat(robotsTagFor("http://shop.nova.localhost:8080/shop/")).isNull();
        assertThat(robotsTagFor("http://shop.nordlys.localhost:8080/shop/")).isNull();
        assertThat(robotsTagFor("http://localhost:8080/shop/")).isNull();
    }

    @Test
    void anUnmappedHostIsTheDefaultTenantJustAsTheTenantStampDecides() {
        // 127.0.0.1 is not in this registry's host list; genalpha is open
        assertThat(robotsTagFor("http://127.0.0.1:8080/shop/")).isNull();
    }

    @Test
    void anUnmappedHostUnderADarkDefaultTenantIsStillDark() {
        TenantHosts hosts = new TenantHosts();
        hosts.setDefaultTenant("fjord");
        hosts.setRegistry(List.of(entry("fjord", "dark", "shop.fjord.localhost")));
        MockServerWebExchange exchange = MockServerWebExchange
                .from(MockServerHttpRequest.get("http://unknown.example:8080/shop/"));
        new CrawlerVisibilityFilter(true, hosts).filter(exchange, e -> Mono.empty()).block();
        exchange.getResponse().setComplete().block();

        assertThat(exchange.getResponse().getHeaders().getFirst(CrawlerVisibilityFilter.HEADER))
                .isEqualTo("noindex, nofollow");
    }

    @Test
    void theDefaultForAnEntryThatNamesNoPostureIsTheCautiousOneButNotDark() {
        // a tenant file that forgets the key must not accidentally publish a
        // tenant -- and must not accidentally delist one either
        TenantHosts.Entry silent = new TenantHosts.Entry();
        assertThat(silent.getAiVisibility()).isEqualTo("search-only");
        assertThat(silent.isDark()).isFalse();
    }
}
