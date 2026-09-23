package com.bss.gateway;

import com.bss.gateway.tenant.TenantHosts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.stream.Collectors;

/**
 * The response headers every page and every API answer carries.
 *
 * The fleet sent none of these. That mattered most for the consoles, which
 * still build some DOM with innerHTML: without a content security policy, one
 * unescaped customer name is a script running with a care agent's session, and
 * without a frame rule any site could put the console in an invisible frame and
 * borrow the agent's clicks.
 *
 * The policy is set here rather than in each of the forty components because
 * the browser only ever talks to the gateway; a component's own headers would
 * never reach a page. It applies to API answers too, where it costs nothing and
 * still does something: a JSON document a browser is tricked into rendering as
 * HTML is exactly what nosniff is for.
 *
 * What the policy deliberately allows, and why:
 *
 *  - style-src 'unsafe-inline' — pages carry a handful of style attributes.
 *    Styles cannot execute; this is the cheap half of the policy to relax.
 *  - img-src https: — product art comes from an operator's own media library,
 *    which is a vendor URL we do not control.
 *  - script-src 'unsafe-eval' ON THE ADMIN CONSOLE ONLY — its command palette
 *    resolves globals declared with let/const in sibling classic scripts, which
 *    are not reachable as window properties, so it falls back to eval. Note
 *    what this does NOT give up: 'unsafe-eval' permits eval, never inline
 *    script, so an injected <img onerror=...> is still refused. The innerHTML
 *    risk is covered on every page including this one. Removing it means giving
 *    the palette a registry to read instead of a scope to search.
 *
 * HSTS is sent only when the request arrived over TLS. Sending it over plain
 * HTTP is meaningless, and sending it from a local fleet would pin a developer's
 * browser to https://localhost.
 */
@Component
public class SecurityHeadersFilter implements GlobalFilter, Ordered {

    private static final String BASE_POLICY =
            "default-src 'self'; "
            + "script-src 'self'%s; "
            + "style-src 'self' 'unsafe-inline'; "
            + "img-src 'self' data: blob: https:; "
            + "font-src 'self' data:; "
            + "connect-src 'self'%s; "
            + "object-src 'none'; "
            + "base-uri 'self'; "
            + "form-action 'self'; "
            + "frame-ancestors 'self'";

    /** The one path whose page needs eval; everything else gets the strict policy. */
    private static final String CONSOLE_PATH = "/console";

    private final boolean enabled;
    private final String strict;
    private final String console;

    public SecurityHeadersFilter(@Value("${bss.gateway.security-headers:true}") boolean enabled,
            TenantHosts tenants) {
        this.enabled = enabled;
        // Every channel signs in against its tenant's OIDC issuer and then
        // exchanges the code for a token from the browser. The issuer is a
        // different origin from the gateway, so a bare connect-src 'self'
        // does not merely tighten the page — it stops anyone logging in.
        // The registry already names every issuer this deployment serves, so
        // the policy is built from it rather than from a second list that
        // could drift away from the first.
        String idps = tenants.getRegistry().stream()
                .map(TenantHosts.Entry::getIssuer)
                .map(SecurityHeadersFilter::originOf)
                .filter(o -> o != null && !o.isBlank())
                .distinct()
                .sorted()
                .collect(Collectors.joining(" "));
        String connect = idps.isEmpty() ? "" : " " + idps;
        this.strict = BASE_POLICY.formatted("", connect);
        this.console = BASE_POLICY.formatted(" 'unsafe-eval'", connect);
    }

    /** scheme://host[:port] — a source expression, never a path. */
    private static String originOf(String issuer) {
        if (issuer == null || issuer.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(issuer.trim());
            if (uri.getScheme() == null || uri.getHost() == null) {
                return null;
            }
            return uri.getScheme() + "://" + uri.getHost()
                    + (uri.getPort() > -1 ? ":" + uri.getPort() : "");
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!enabled) {
            return chain.filter(exchange);
        }
        // beforeCommit, because a proxied response's headers are written when
        // the downstream answer arrives, which is after this filter returns
        exchange.getResponse().beforeCommit(() -> {
            HttpHeaders h = exchange.getResponse().getHeaders();
            String path = exchange.getRequest().getPath().value();
            setIfAbsent(h, "Content-Security-Policy",
                    path.startsWith(CONSOLE_PATH) ? console : strict);
            setIfAbsent(h, "X-Content-Type-Options", "nosniff");
            setIfAbsent(h, "X-Frame-Options", "SAMEORIGIN");
            setIfAbsent(h, "Referrer-Policy", "no-referrer");
            setIfAbsent(h, "Permissions-Policy", "camera=(), microphone=()");
            if (overTls(exchange)) {
                setIfAbsent(h, "Strict-Transport-Security", "max-age=31536000; includeSubDomains");
            }
            return Mono.empty();
        });
        return chain.filter(exchange);
    }

    /** A component that sets its own policy for a reason keeps it. */
    private static void setIfAbsent(HttpHeaders headers, String name, String value) {
        if (!headers.containsKey(name)) {
            headers.set(name, value);
        }
    }

    private static boolean overTls(ServerWebExchange exchange) {
        String forwarded = exchange.getRequest().getHeaders().getFirst("X-Forwarded-Proto");
        if (forwarded != null && !forwarded.isBlank()) {
            // a proxy may forward a list; the client's own protocol is the first
            return "https".equalsIgnoreCase(forwarded.split(",")[0].trim());
        }
        return "https".equalsIgnoreCase(exchange.getRequest().getURI().getScheme());
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }
}
