package com.bss.som.security;

import com.bss.som.api.ApiConstants;
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
 * other issuer are rejected before authorization runs. The SOM's read APIs
 * (what the production layer did, what is running) need "service:read";
 * there are no write endpoints — orchestration is event-driven.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final String READ = "service:read";
    private static final String WRITE = "service:write";

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, ClaimAuthoritiesConverter authoritiesConverter,
            TenantRegistry tenants) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/actuator/prometheus", "/v3/api-docs/**",
                                "/swagger-ui/**", "/swagger-ui.html", "/.well-known/genalpha-component.json").permitAll()
                        // the shop's number picker: PUBLIC BY DESIGN. A shopper who
                        // has not signed in must be able to see the numbers on offer
                        // before choosing a plan; nothing is reserved or consumed by
                        // looking, and the hand is capped at 12.
                        .requestMatchers(org.springframework.http.HttpMethod.GET,
                                "/tmf-api/resourcePoolManagement/v4/numberOffer").permitAll()
                        // the fibre owner's OSS activation callback: an external
                        // system, not a fleet identity. Anonymous HERE and
                        // credentialled inside — the callback URL we handed the owner
                        // ends in a token bound to the order and derived from the
                        // ordering tenant's wholesale secret (WholesaleDoorAuth).
                        .requestMatchers(HttpMethod.POST,
                                "/tmf-api/serviceOrdering/v4/wholesaleAccessOrder/*/notification/*").permitAll()
                        // our OWN Sonata provider face: a retailer's BSS places an
                        // access-seeker order here. It holds no token of ours, so the
                        // door is anonymous HERE and the body is HMAC-signed inside
                        // with the wholesale secret of the operator X-Tenant-Id names
                        // — which is what makes that header safe to believe.
                        .requestMatchers(HttpMethod.POST,
                                "/mefApi/serviceOrdering/v1/serviceOrder").permitAll()
                        .requestMatchers("/tmf-api/serviceTestManagement/v4/**").authenticated()
                        .requestMatchers("/som/v1/importService").hasAuthority("service:write")
                        .requestMatchers("/tmf-api/resourceInventoryManagement/v4/**").hasAuthority("service:write")
                        .requestMatchers(HttpMethod.POST, ApiConstants.ORDER_BASE + "/serviceOrder",
                                "/tmf-api/serviceOrdering/v3/serviceOrder").hasAuthority(WRITE)
                        // wholesale settlement statements: the fixed-wholesale console reads
                        // them, so a wholesale operator sees the book with wholesale:admin
                        // as well as the general service:read.
                        .requestMatchers(HttpMethod.GET,
                                ApiConstants.ORDER_BASE + "/wholesaleProviderSettlement",
                                ApiConstants.ORDER_BASE + "/wholesaleSettlement")
                                .hasAnyAuthority(READ, "wholesale:admin")
                        .requestMatchers(HttpMethod.GET, ApiConstants.ORDER_BASE + "/**",
                                "/tmf-api/serviceOrdering/v3/**",
                                ApiConstants.INVENTORY_BASE + "/**",
                                // TMF640: the activation face reads like the inventory,
                                // writes like it (service:write below)
                                ApiConstants.ACTIVATION_BASE + "/**",
                                "/tmf-api/resourcePoolManagement/v4/**",
                                "/tmf-api/intentManagement/v4/**").hasAuthority(READ)
                        // customer SELF-CARE: any authenticated owner may act on
                        // THEIR OWN line (owner check in every handler) — reset a
                        // PIN, replace a lost SIM, change number, pause/resume,
                        // or cancel the subscription
                        .requestMatchers(HttpMethod.POST,
                                ApiConstants.INVENTORY_BASE + "/service/*/sim/resetPin",
                                ApiConstants.INVENTORY_BASE + "/service/*/sim/replace",
                                ApiConstants.INVENTORY_BASE + "/service/*/changeNumber",
                                ApiConstants.INVENTORY_BASE + "/service/*/suspend",
                                ApiConstants.INVENTORY_BASE + "/service/*/resume",
                                ApiConstants.INVENTORY_BASE + "/service/*/terminate",
                                ApiConstants.INVENTORY_BASE + "/service/*/transfer",
                                ApiConstants.INVENTORY_BASE + "/service/*/diagnose",
                                ApiConstants.INVENTORY_BASE + "/service/*/cpe/restart").authenticated()
                        .requestMatchers(HttpMethod.POST,
                                "/tmf-api/resourcePoolManagement/v4/**",
                                "/tmf-api/intentManagement/v4/**",
                                ApiConstants.INVENTORY_BASE + "/**",
                                ApiConstants.ACTIVATION_BASE + "/**").hasAuthority(WRITE)
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
