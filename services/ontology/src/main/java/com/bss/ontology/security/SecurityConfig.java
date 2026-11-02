package com.bss.ontology.security;

import com.bss.ontology.api.ApiConstants;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationManagerResolver;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;
import org.springframework.security.oauth2.server.resource.authentication.JwtIssuerAuthenticationManagerResolver;
import org.springframework.security.web.SecurityFilterChain;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Any signed-in person or agent may read the ontology and ask what they may do:
 * the registry needs no role of its own, because every action carries its own
 * permissions and every downstream call runs with the caller's token — a
 * customer's delegated token upgrades that customer's line and nobody else's.
 * The component's self-description is public, like a manifest.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, ClaimAuthoritiesConverter authoritiesConverter,
            TenantRegistry tenants, ProtectedResourceMetadata protectedResource) throws Exception {
        http
                // OFF ON PURPOSE. A cross-site request forgery works by making the
                // browser send a request carrying its AMBIENT credentials — a cookie
                // it attaches by itself. This API has none: the session policy on the
                // next line is STATELESS, nothing here reads a cookie, and the only
                // credential is a bearer token a caller must attach deliberately,
                // which an attacker's page cannot do. Enabling CSRF here would break
                // every channel and protect nothing. Recorded as ADR 0024, and the
                // CodeQL alerts are dismissed against that decision rather than left
                // open to bury real findings.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/actuator/prometheus", ApiConstants.WELL_KNOWN).permitAll()
                        .requestMatchers(ApiConstants.BASE_PATH + "/**").authenticated()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .authenticationManagerResolver(tenantIssuerResolver(tenants, authoritiesConverter))
                        // RFC 9728, which MCP 2026-07-28 requires of a protected MCP server: where
                        // to get a token for this resource. Spring Security serves the document at
                        // /.well-known/oauth-protected-resource[/<resource path>] from its own filter,
                        // before authorization — so it is anonymous WITHOUT a permitAll line, and
                        // ops/security/public-paths.txt names it in a comment rather than an entry.
                        // Left alone it names NO authorization server (the issuer resolver is
                        // per-tenant, so Spring cannot know one) and the resource as this pod's
                        // internal URL; both are filled here. The tenant is TenantScope's: the
                        // gateway's hostname stamp for an anonymous caller, never a body or a claim.
                        .protectedResourceMetadata(meta -> meta.protectedResourceMetadataCustomizer(
                                b -> protectedResource.describe(b)))
                        // the 401's resource_metadata pointer, at the public origin, not this pod's
                        .authenticationEntryPoint(protectedResource.entryPoint()));
        return http.build();
    }

    private AuthenticationManagerResolver<HttpServletRequest> tenantIssuerResolver(
            TenantRegistry tenants, ClaimAuthoritiesConverter authoritiesConverter) {
        JwtAuthenticationConverter jwtConverter = new JwtAuthenticationConverter();
        jwtConverter.setJwtGrantedAuthoritiesConverter(authoritiesConverter);
        Map<String, AuthenticationManager> managers = new ConcurrentHashMap<>();
        return new JwtIssuerAuthenticationManagerResolver(issuer -> {
            TenantRegistry.TenantEntry tenant = tenants.byIssuer(issuer);
            if (tenant == null) {
                return null;
            }
            return managers.computeIfAbsent(issuer, iss -> {
                NimbusJwtDecoder decoder = (tenant.getJwksUri() == null || tenant.getJwksUri().isBlank()
                        ? NimbusJwtDecoder.withIssuerLocation(iss)
                        : NimbusJwtDecoder.withJwkSetUri(tenant.getJwksUri())).build();
                decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(iss));
                JwtAuthenticationProvider provider = new JwtAuthenticationProvider(decoder);
                provider.setJwtAuthenticationConverter(jwtConverter);
                return provider::authenticate;
            });
        });
    }
}
