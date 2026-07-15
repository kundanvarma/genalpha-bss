package com.bss.communication.security;

import com.bss.communication.api.ApiConstants;
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
 * other issuer are rejected before authorization runs. Communications need
 * "communication:read"/"communication:write" and are party-scoped for
 * customers. Health and the OpenAPI docs stay open.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final String READ = "communication:read";
    private static final String WRITE = "communication:write";

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, ClaimAuthoritiesConverter authoritiesConverter,
            TenantRegistry tenants) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/actuator/prometheus", "/v3/api-docs/**",
                                "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        // the ESP's webhook has no OIDC token; each event is
                        // verified against its tenant's own ESP key inside
                        .requestMatchers(HttpMethod.POST, "/esp/v1/event").permitAll()
                        .requestMatchers(HttpMethod.GET, "/esp/v1/suppression").hasAuthority(READ)
                        .requestMatchers(HttpMethod.GET, ApiConstants.BASE_PATH + "/**").hasAuthority(READ)
                        .requestMatchers(HttpMethod.POST, ApiConstants.BASE_PATH + "/**").hasAuthority(WRITE)
                        .requestMatchers(HttpMethod.PATCH, ApiConstants.BASE_PATH + "/**").hasAuthority(WRITE)
                        .requestMatchers(HttpMethod.DELETE, ApiConstants.BASE_PATH + "/**").hasAuthority(WRITE)
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
