package com.bss.ontology;

import com.bss.ontology.controller.McpController;
import com.bss.ontology.registry.Registry;
import com.bss.ontology.security.ClaimAuthoritiesConverter;
import com.bss.ontology.security.ProtectedResourceMetadata;
import com.bss.ontology.security.SecurityConfig;
import com.bss.ontology.security.TenantRegistry;
import com.bss.ontology.security.TenantScope;
import com.bss.ontology.service.ActionCheckService;
import com.bss.ontology.service.ActionExecuteService;
import com.bss.ontology.service.ContextService;
import com.bss.ontology.service.ExplainService;
import com.bss.ontology.service.McpApps;
import com.bss.ontology.service.McpResources;
import com.bss.ontology.service.RecommendationService;
import com.bss.ontology.service.UpgradeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
 * THE ANONYMOUS SURFACE SPRING SERVES BY ITSELF (#428) — the ontology's twin of
 * {@code services/agreement/.../FrameworkSurfaceTest}, for the one copy whose
 * resource server is customised (an RFC 9728 customizer and its own entry
 * point). Same KNOWN table, same canaries, same assertion: the filters in the
 * real chain have all been measured, and what answers without a token equals
 * this component's {@code framework:} entries in
 * {@code ops/security/public-paths.txt}. Keep the two KNOWN tables identical;
 * {@code ops/security/security_config_drift.py} holds every other copy to the
 * agreement's entries.
 */
@WebMvcTest(controllers = McpController.class, properties = {
        "bss.tenants.default-tenant=genalpha",
        "bss.tenants.registry[0].id=genalpha",
        "bss.tenants.registry[0].issuer=https://idp.genalpha.test/realms/bss"})
@Import({SecurityConfig.class, TenantScope.class, TenantRegistry.class, ClaimAuthoritiesConverter.class,
        ProtectedResourceMetadata.class, Registry.class, McpResources.class, McpApps.class,
        FrameworkSurfaceTest.Props.class})
class FrameworkSurfaceTest {

    private static final String SERVICE = "ontology";

    @TestConfiguration
    @EnableConfigurationProperties
    static class Props {
    }

    @MockitoBean
    private ActionCheckService checks;
    @MockitoBean
    private ActionExecuteService executes;
    @MockitoBean
    private UpgradeService upgrades;
    @MockitoBean
    private ExplainService explain;
    @MockitoBean
    private ContextService context;
    @MockitoBean
    private RecommendationService recommendations;

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
