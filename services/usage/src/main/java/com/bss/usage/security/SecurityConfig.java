package com.bss.usage.security;

import com.bss.usage.api.ApiConstants;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * OAuth2 resource server: usage records are personal consumption data. Reads
 * require "usage:read" (customers party-scoped); writes "usage:write" — the
 * ingest endpoint is the mediation/OCS seam, machine and back-office only.
 * Health and the OpenAPI docs stay open for probes and discovery.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {


    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, ClaimAuthoritiesConverter authoritiesConverter)
            throws Exception {
        JwtAuthenticationConverter jwtConverter = new JwtAuthenticationConverter();
        jwtConverter.setJwtGrantedAuthoritiesConverter(authoritiesConverter);
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/actuator/prometheus", "/v3/api-docs/**",
                                "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers(HttpMethod.GET, ApiConstants.BASE_PATH + "/**").hasAuthority("usage:read")
                        .requestMatchers(HttpMethod.GET, ApiConstants.CONSUMPTION_BASE_PATH + "/**").hasAuthority("usage:read")
                        .requestMatchers(HttpMethod.POST, ApiConstants.BASE_PATH + "/**").hasAuthority("usage:write")
                        .requestMatchers(HttpMethod.DELETE, ApiConstants.BASE_PATH + "/**").hasAuthority("usage:write")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtConverter)));
        return http.build();
    }
}
