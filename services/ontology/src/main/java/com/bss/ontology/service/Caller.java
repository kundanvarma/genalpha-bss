package com.bss.ontology.service;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Who is asking: their bearer (forwarded downstream as-is), their subject, their
 * authorities, the sales channel they came through, and the tenant. A token that
 * carries the {@code customer} role is a customer — it may act on its own things
 * only, whatever else it holds.
 */
public record Caller(String bearer, String subject, Set<String> roles, String channel, String tenant) {

    public static final String CHANNEL_HEADER = "X-Channel";

    public boolean isCustomer() {
        return roles.contains("customer");
    }

    public boolean has(String role) {
        return roles.contains(role);
    }

    public static Caller current(HttpServletRequest request, String tenant) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String bearer = "";
        String subject = auth == null ? "" : auth.getName();
        Set<String> roles = auth == null ? Set.of()
                : auth.getAuthorities().stream().map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
        if (auth instanceof JwtAuthenticationToken jwt) {
            bearer = jwt.getToken().getTokenValue();
            subject = jwt.getToken().getSubject();
        }
        String channel = request == null ? null : request.getHeader(CHANNEL_HEADER);
        return new Caller(bearer, subject, roles, channel == null || channel.isBlank() ? "web" : channel.trim(), tenant);
    }
}
