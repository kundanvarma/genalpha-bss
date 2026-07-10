package com.bss.stock.security;

import com.bss.stock.api.ApiConstants;
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
 * validates JWTs from whatever OIDC issuer OIDC_ISSUER_URI points at. Stock
 * levels are shop-window information ("in stock", "only 3 left"), so reads are
 * anonymous like the catalog; every write requires "stock:write". Health and
 * the OpenAPI docs stay open for probes and discovery.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final String WRITE = "stock:write";

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
                        .requestMatchers(HttpMethod.GET, ApiConstants.BASE_PATH + "/**").permitAll()
                        .requestMatchers(HttpMethod.POST, ApiConstants.BASE_PATH + "/**").hasAuthority(WRITE)
                        .requestMatchers(HttpMethod.PATCH, ApiConstants.BASE_PATH + "/**").hasAuthority(WRITE)
                        .requestMatchers(HttpMethod.DELETE, ApiConstants.BASE_PATH + "/**").hasAuthority(WRITE)
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtConverter)));
        return http.build();
    }
}
