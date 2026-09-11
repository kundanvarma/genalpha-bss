package com.bss.entitlement.security;

import com.bss.entitlement.api.ApiConstants;
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
 * Two faces, two trusts. The TS.43 door ({@code /ts43/**}) is reached by
 * PHONES: it is anonymous at the HTTP layer because its authentication is
 * EAP-AKA against the subscriber's SIM (or a token the server itself issued),
 * exactly as GSMA TS.43 §2.8 specifies. The BSS face is an OAuth2 resource
 * server, multi-issuer like every component: {@code entitlement:read} to look,
 * {@code entitlement:write} to bind subscribers and drive re-configuration.
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
                                "/swagger-ui/**", "/swagger-ui.html", "/.well-known/genalpha-component.json").permitAll()
                        // the device door: EAP-AKA / ECS token, not a BSS login
                        .requestMatchers(ApiConstants.TS43_PATH, ApiConstants.TS43_PATH + "/**").permitAll()
                        // the RCS client's configuration door (GSMA RCC.14): the SIM is the credential
                        .requestMatchers("/rcs/**").permitAll()
                        .requestMatchers(HttpMethod.GET, BASE + "/**")
                                .hasAnyAuthority("entitlement:read", "entitlement:write")
                        .requestMatchers(HttpMethod.POST, BASE + "/**").hasAuthority("entitlement:write")
                        .requestMatchers(HttpMethod.PUT, BASE + "/**").hasAuthority("entitlement:write")
                        .requestMatchers(HttpMethod.PATCH, BASE + "/**").hasAuthority("entitlement:write")
                        .requestMatchers(HttpMethod.DELETE, BASE + "/**").hasAuthority("entitlement:write")
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
