package com.bss.party.security;

import com.bss.party.api.ApiConstants;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * OAuth2 resource server: this service never issues tokens or performs login; it
 * validates JWTs from whatever OIDC issuer OIDC_ISSUER_URI points at. Reads
 * require the "party:read" authority, writes "party:write". Health and the
 * OpenAPI docs stay open for probes and discovery.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final String READ = "party:read";
    private static final String WRITE = "party:write";

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, ClaimAuthoritiesConverter authoritiesConverter)
            throws Exception {
        JwtAuthenticationConverter jwtConverter = new JwtAuthenticationConverter();
        jwtConverter.setJwtGrantedAuthoritiesConverter(authoritiesConverter);
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/v3/api-docs/**",
                                "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers(HttpMethod.GET, ApiConstants.PARTY_BASE + "/**",
                                ApiConstants.ACCOUNT_BASE + "/**").hasAuthority(READ)
                        .requestMatchers(HttpMethod.POST, ApiConstants.PARTY_BASE + "/**",
                                ApiConstants.ACCOUNT_BASE + "/**").hasAuthority(WRITE)
                        .requestMatchers(HttpMethod.PATCH, ApiConstants.PARTY_BASE + "/**",
                                ApiConstants.ACCOUNT_BASE + "/**").hasAuthority(WRITE)
                        .requestMatchers(HttpMethod.DELETE, ApiConstants.PARTY_BASE + "/**",
                                ApiConstants.ACCOUNT_BASE + "/**").hasAuthority(WRITE)
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtConverter)));
        return http.build();
    }
}
