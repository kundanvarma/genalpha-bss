package com.bss.paymentmethod.security;

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
        return customer ? Optional.ofNullable(auth.getName()) : Optional.empty();
    }
}
