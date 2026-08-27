package com.bss.devicecommerce.security;

import com.bss.devicecommerce.api.ApiConstants;
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
 * other issuer are rejected before authorization runs. Customers drive
 * their OWN trade-ins, agreements and withdrawals (party-scoped in the
 * service layer); grading, the residual table, blacklist flags, instalment
 * feeds and financier webhooks are staff/machine — "device:write".
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final String BASE = ApiConstants.BASE_PATH;

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, ClaimAuthoritiesConverter authoritiesConverter,
            TenantRegistry tenants) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/actuator/prometheus", "/v3/api-docs/**",
                                "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        // Back-office and machine seams FIRST (order matters).
                        .requestMatchers(HttpMethod.POST, BASE + "/tradeInValuation/*/grading",
                                BASE + "/tradeInValuation/*/inTransit",
                                BASE + "/deviceAgreement/*/recordInstallment",
                                BASE + "/deviceAgreement/*/settle",
                                BASE + "/deviceAgreement/*/payoutWebhook",
                                BASE + "/deviceFlag",
                                BASE + "/tradeInResidual").hasAuthority("device:write")
                        // A customer may run their OWN device lifecycle (scoped in the service).
                        .requestMatchers(HttpMethod.POST, BASE + "/financingQuote",
                                BASE + "/deviceAgreement",
                                BASE + "/deviceAgreement/*/swap",
                                BASE + "/tradeInValuation",
                                BASE + "/tradeInValuation/*/accept",
                                BASE + "/tradeInValuation/*/acceptRevaluation",
                                BASE + "/tradeInValuation/*/rejectRevaluation",
                                BASE + "/withdrawalCase")
                                .hasAnyAuthority("device:write", "customer")
                        .requestMatchers(HttpMethod.GET, BASE + "/**")
                                .hasAnyAuthority("device:read", "customer")
                        .requestMatchers(HttpMethod.POST, BASE + "/**").hasAuthority("device:write")
                        .requestMatchers(HttpMethod.PATCH, BASE + "/**").hasAuthority("device:write")
                        .requestMatchers(HttpMethod.DELETE, BASE + "/**").hasAuthority("device:write")
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
