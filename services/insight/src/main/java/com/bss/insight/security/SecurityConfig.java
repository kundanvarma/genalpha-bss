package com.bss.insight.security;

import com.bss.insight.api.ApiConstants;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
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
 * OAuth2 resource server, multi-issuer: each tenant in the registry is a
 * trusted OIDC issuer with its own (lazily built) decoder; tokens from any
 * other issuer are rejected before authorization runs. Reading the library
 * needs "knowledge:read" (customers, agents and product owners all carry
 * it); WHAT each reader sees is the service's audience filter, from the
 * token. Writing needs "knowledge:write" — the operator's editors.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {


    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, ClaimAuthoritiesConverter authoritiesConverter,
            TenantRegistry tenants) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/actuator/prometheus", "/v3/api-docs/**",
                                "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        // the collector serves GUESTS: consent, breadcrumbs and the
                        // experience question are anonymous by nature (tenant from the
                        // gateway's hostname mapping); consent gates every write inside
                        .requestMatchers(HttpMethod.POST, ApiConstants.BASE_PATH + "/consent",
                                ApiConstants.BASE_PATH + "/event").permitAll()
                        .requestMatchers(HttpMethod.GET, ApiConstants.BASE_PATH + "/experience").permitAll()
                        // the stitch needs a verified token — the subject IS the party
                        .requestMatchers(HttpMethod.POST, ApiConstants.BASE_PATH + "/stitch").authenticated()
                        // the raw profile is back-office only
                        .requestMatchers(HttpMethod.GET, ApiConstants.BASE_PATH + "/profile",
                                ApiConstants.BASE_PATH + "/partyProfile",
                                ApiConstants.BASE_PATH + "/segmentMembers",
                                ApiConstants.BASE_PATH + "/audiences").hasAuthority("insight:read")
                        // CDP backfill: one-shot admin ingest of existing customers' traits
                        .requestMatchers(HttpMethod.POST, ApiConstants.BASE_PATH + "/traits/backfill")
                                .hasAuthority("roles:admin")
                        // saved audiences (authoring + member resolution) are back-office
                        .requestMatchers(HttpMethod.GET, ApiConstants.BASE_PATH + "/audience/**")
                                .hasAuthority("insight:read")
                        .requestMatchers(HttpMethod.POST, ApiConstants.BASE_PATH + "/audience")
                                .hasAuthority("insight:read")
                        .requestMatchers(HttpMethod.DELETE, ApiConstants.BASE_PATH + "/audience/*")
                                .hasAuthority("insight:read")
                        // audience activation + materialization
                        .requestMatchers(HttpMethod.POST, ApiConstants.BASE_PATH + "/audience/*/activate",
                                ApiConstants.BASE_PATH + "/audience/*/refresh")
                                .hasAuthority("insight:read")
                        // auto-refresh scheduler ops surface (status/pause/resume/run)
                        .requestMatchers(HttpMethod.GET, ApiConstants.BASE_PATH + "/refresh/status").hasAuthority("insight:read")
                        .requestMatchers(HttpMethod.POST, ApiConstants.BASE_PATH + "/refresh/**").hasAuthority("insight:read")
                        .requestMatchers(HttpMethod.PATCH, ApiConstants.BASE_PATH + "/audience/**")
                                .hasAuthority("insight:read")
                        // prospect capture + list import (back-office marketing)
                        .requestMatchers(HttpMethod.GET, ApiConstants.BASE_PATH + "/prospect").hasAuthority("insight:read")
                        .requestMatchers(HttpMethod.POST, ApiConstants.BASE_PATH + "/prospect/import")
                                .hasAuthority("insight:read")
                        // social listening (mentions + sentiment)
                        .requestMatchers(HttpMethod.GET, ApiConstants.BASE_PATH + "/listening/**").hasAuthority("insight:read")
                        .requestMatchers(HttpMethod.POST, ApiConstants.BASE_PATH + "/listening/sync")
                                .hasAuthority("insight:read")
                        // social care (inbound DMs -> triage -> ticket requests)
                        .requestMatchers(HttpMethod.GET, ApiConstants.BASE_PATH + "/care/**").hasAuthority("insight:read")
                        .requestMatchers(HttpMethod.POST, ApiConstants.BASE_PATH + "/care/sync")
                                .hasAuthority("insight:read")
                        // organic publishing (post to the brand handle)
                        .requestMatchers(HttpMethod.GET, ApiConstants.BASE_PATH + "/social/posts").hasAuthority("insight:read")
                        .requestMatchers(HttpMethod.POST, ApiConstants.BASE_PATH + "/social/publish")
                                .hasAuthority("insight:read")
                        .requestMatchers("/privacy/v1/**").authenticated()
                .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .authenticationManagerResolver(tenantIssuerResolver(tenants, authoritiesConverter)));
        return http.build();
    }

    /**
     * Issuer -> AuthenticationManager, built on first token per issuer so
     * startup needs no IdP round-trips. A registered issuer gets a decoder
     * (explicit backchannel JWKS, or issuer discovery when none is set); an
     * unregistered issuer resolves to null and the token is rejected.
     */
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
