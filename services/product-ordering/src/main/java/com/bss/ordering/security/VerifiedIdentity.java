package com.bss.ordering.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * Has the buyer proven a real-world identity to the required assurance
 * level? In production that proof is a BankID/Vipps step-up brokered by the
 * IdP, surfaced in the token as an authentication-context-class ("acr") or a
 * verified-identity claim. This reader is deliberately IdP-agnostic: it
 * trusts the claim, never the mechanism — swapping BankID for MitID or itsme
 * is a Keycloak brokering change, no code here.
 */
@Component
public class VerifiedIdentity {

    private final Set<String> acceptedAcr;

    public VerifiedIdentity(
            @Value("${bss.ordering.verified-identity.accepted-acr:bankid,high,urn:grn:authn:se:bankid}")
            String acceptedAcr) {
        this.acceptedAcr = Set.of(acceptedAcr.toLowerCase().split("\\s*,\\s*"));
    }

    public boolean isVerified() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof JwtAuthenticationToken jwtAuth)) {
            return false;
        }
        Jwt jwt = jwtAuth.getToken();
        // An explicit verified-identity claim wins (a broker may set it directly).
        if (Boolean.TRUE.equals(jwt.getClaim("verified_identity"))
                || "true".equalsIgnoreCase(String.valueOf(jwt.getClaims().get("verified_identity")))) {
            return true;
        }
        // Otherwise an accepted authentication-context class (acr / amr).
        Object acr = jwt.getClaims().get("acr");
        if (acr != null && acceptedAcr.contains(String.valueOf(acr).toLowerCase())) {
            return true;
        }
        Object amr = jwt.getClaims().get("amr");
        if (amr instanceof List<?> methods) {
            return methods.stream().map(m -> String.valueOf(m).toLowerCase()).anyMatch(acceptedAcr::contains);
        }
        return false;
    }
}
