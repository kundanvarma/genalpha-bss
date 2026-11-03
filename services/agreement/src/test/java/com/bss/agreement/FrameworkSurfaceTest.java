package com.bss.agreement;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * THE ANONYMOUS SURFACE SPRING SERVES BY ITSELF (#428). A {@code permitAll} line
 * is not the only way a path becomes anonymous: some Spring Security filters
 * answer a request themselves, BEFORE the authorization filter runs. Two do in
 * this component's chain, measured here and on the live fleet (3 Nov 2026):
 * <ul>
 *   <li>{@code OAuth2ProtectedResourceMetadataFilter} — on by default with
 *       {@code oauth2ResourceServer()} since Spring Security 7, no way to switch it
 *       off: GET {@code /.well-known/oauth-protected-resource[/**]} answers 200
 *       with an RFC 9728 document;</li>
 *   <li>{@code LogoutFilter} — on by default in every {@code HttpSecurity}: any
 *       method on {@code /logout} answers 302 (any method, because CSRF is off).</li>
 * </ul>
 * The static drift check ({@code ops/security/security_config_drift.py}) reads
 * permitAll lines and cannot see either. This test is its measured half: it
 * boots this component's real filter chain and asserts that
 * <ol>
 *   <li>every filter in the chain is one whose behaviour has been measured — a
 *       Spring upgrade that adds a filter fails here, before it can serve a
 *       path nobody declared;</li>
 *   <li>the set of probed paths that answer without a token equals the
 *       {@code framework:} entries this component declares in
 *       {@code ops/security/public-paths.txt}, no more and no fewer.</li>
 * </ol>
 * The drift check then requires every other copy with the same configurer shape
 * to declare the same framework paths. The ontology carries a twin of this test
 * for its customised shape; keep the two KNOWN tables identical.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FrameworkSurfaceTest {

    private static final String SERVICE = "agreement";

    /**
     * Every filter class measured so far, and the path it answers by itself —
     * empty when it serves nothing (it decorates, authenticates or rejects).
     */
    private static final Map<String, String> KNOWN = Map.ofEntries(
            Map.entry("org.springframework.security.web.session.DisableEncodeUrlFilter", ""),
            Map.entry("org.springframework.security.web.context.request.async.WebAsyncManagerIntegrationFilter", ""),
            Map.entry("org.springframework.security.web.context.SecurityContextHolderFilter", ""),
            Map.entry("org.springframework.security.web.header.HeaderWriterFilter", ""),
            Map.entry("org.springframework.security.web.authentication.logout.LogoutFilter", "/logout"),
            Map.entry("org.springframework.security.oauth2.server.resource.web.OAuth2ProtectedResourceMetadataFilter",
                    "/.well-known/oauth-protected-resource/**"),
            Map.entry("org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter", ""),
            Map.entry("org.springframework.security.web.savedrequest.RequestCacheAwareFilter", ""),
            Map.entry("org.springframework.security.web.servletapi.SecurityContextHolderAwareRequestFilter", ""),
            Map.entry("org.springframework.security.web.authentication.AnonymousAuthenticationFilter", ""),
            Map.entry("org.springframework.security.web.session.SessionManagementFilter", ""),
            Map.entry("org.springframework.security.web.access.ExceptionTranslationFilter", ""),
            Map.entry("org.springframework.security.web.access.intercept.AuthorizationFilter", ""));

    /**
     * Paths Spring Security and Spring Boot answer by themselves when some
     * feature is switched on (form login, one-time tokens, WebAuthn, OAuth2
     * login, SAML, the authorization server, the change-password redirect, the
     * default login page's stylesheet, Boot's error page). None may answer here
     * unless declared — a canary for a feature switched on by a dependency.
     */
    private static final List<String> CANARIES = List.of(
            "/login", "/login/ott", "/ott/generate", "/default-ui.css", "/error", "/favicon.ico",
            "/webauthn/register/options", "/webauthn/authenticate/options", "/login/webauthn",
            "/.well-known/change-password", "/.well-known/openid-configuration",
            "/.well-known/oauth-authorization-server", "/oauth2/authorization/probe", "/login/oauth2/code/probe",
            "/oauth2/jwks", "/oauth2/token", "/saml2/authenticate/probe", "/saml2/service-provider-metadata/probe",
            "/login/saml2/sso/probe", "/logout/saml2/slo");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private FilterChainProxy chains;

    @Test
    void everyFilterInTheChainHasBeenMeasured() {
        List<String> unmeasured = new ArrayList<>();
        chains.getFilterChains().forEach(chain -> chain.getFilters().forEach(f -> {
            if (!KNOWN.containsKey(f.getClass().getName())) {
                unmeasured.add(f.getClass().getName());
            }
        }));
        assertThat(unmeasured)
                .as("a filter nobody has measured is in the chain; probe what it answers without a token, "
                        + "add it to KNOWN here and in the ontology's twin, and declare its path in public-paths.txt")
                .isEmpty();
    }

    @Test
    void whatAnswersWithoutATokenIsExactlyWhatIsDeclared() throws Exception {
        Set<String> declared = declaredFrameworkPaths();
        Set<String> candidates = new TreeSet<>(CANARIES);
        KNOWN.values().stream().filter(p -> !p.isEmpty()).forEach(candidates::add);
        candidates.addAll(declared);

        Set<String> answered = new TreeSet<>();
        for (String pattern : candidates) {
            for (String path : probesFor(pattern)) {
                for (HttpMethod method : List.of(HttpMethod.GET, HttpMethod.POST)) {
                    int status = mvc.perform(request(method, path)).andReturn().getResponse().getStatus();
                    if (status != 401 && status != 403) {
                        answered.add(pattern + " (" + method + " " + path + " -> " + status + ")");
                    }
                }
            }
        }
        Set<String> answeredPatterns = new TreeSet<>();
        answered.forEach(a -> answeredPatterns.add(a.substring(0, a.indexOf(" ("))));

        assertThat(answeredPatterns)
                .as("anonymous answers measured: " + answered
                        + " — ops/security/public-paths.txt must declare each as '" + SERVICE + " framework:<path>'")
                .isEqualTo(declared);
    }

    /** A pattern ending in /** is probed at its root and one level below. */
    private static List<String> probesFor(String pattern) {
        if (pattern.endsWith("/**")) {
            String root = pattern.substring(0, pattern.length() - 3);
            return List.of(root, root + "/probe/below");
        }
        return List.of(pattern);
    }

    /** This component's framework: entries, read from the fleet's one declaration. */
    private static Set<String> declaredFrameworkPaths() throws Exception {
        Path file = Path.of("").toAbsolutePath().resolve("../../ops/security/public-paths.txt").normalize();
        assertThat(file).as("the fleet's anonymous-surface declaration must be readable from the module").exists();
        Set<String> out = new TreeSet<>();
        for (String line : Files.readAllLines(file)) {
            String body = line.split("#", 2)[0].trim();
            String[] parts = body.split("\\s+", 2);
            if (parts.length == 2 && parts[0].equals(SERVICE) && parts[1].startsWith("framework:")) {
                out.add(parts[1].substring("framework:".length()).trim());
            }
        }
        return out;
    }
}
