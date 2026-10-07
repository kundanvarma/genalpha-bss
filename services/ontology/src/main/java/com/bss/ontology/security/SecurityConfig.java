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
            TenantRegistry tenants) throws Exception {
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
                        .authenticationManagerResolver(tenantIssuerResolver(tenants, authoritiesConverter)));
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
