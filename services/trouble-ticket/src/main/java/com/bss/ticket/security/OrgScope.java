package com.bss.ticket.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Org-scoped requests: an agent's token carries the "agent" role and an "org"
 * claim naming their business unit or partner organisation — their view is
 * confined to that organisation's records. Back-office (no agent role, no
 * customer role) stays unscoped.
 */
@Component
public class OrgScope {

    public static final String AGENT_ROLE = "agent";

    /** The organisation this request is confined to, or empty when unscoped. */
    public Optional<String> scopedOrgId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return Optional.empty();
        }
        boolean agent = auth.getAuthorities().stream()
                .anyMatch(a -> AGENT_ROLE.equals(a.getAuthority()));
        if (!agent || !(auth.getPrincipal() instanceof Jwt jwt)) {
            return Optional.empty();
        }
        return Optional.ofNullable(jwt.getClaimAsString("org"));
    }
}
