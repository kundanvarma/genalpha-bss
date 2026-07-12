package com.bss.party.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Party-scoped requests: a token carrying the "customer" role may only touch
 * resources belonging to its own subject (the party id IS the token subject).
 * Staff and machine tokens carry no "customer" role and stay unscoped — the
 * coarse role checks in SecurityConfig remain their only restriction.
 */
@Component
public class PartyScope {

    public static final String CUSTOMER_ROLE = "customer";

    /** The party id this request is confined to, or empty when unscoped. */
    public Optional<String> scopedPartyId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return Optional.empty();
        }
        boolean customer = auth.getAuthorities().stream()
                .anyMatch(a -> CUSTOMER_ROLE.equals(a.getAuthority()));
        // A business admin manages their organization's people: not confined to
        // self — the services enforce the org boundary instead.
        boolean businessAdmin = auth.getAuthorities().stream()
                .anyMatch(a -> "business:admin".equals(a.getAuthority()));
        return customer && !businessAdmin ? Optional.ofNullable(auth.getName()) : Optional.empty();
    }
}
