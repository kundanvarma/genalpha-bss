package com.bss.communication.controller;

import com.bss.communication.api.ApiConstants;
import com.bss.communication.exception.BadRequestException;
import com.bss.communication.security.PartyScope;
import com.bss.communication.service.MarketingPreferenceService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * The customer's marketing preference centre — self-serve, party-scoped: a
 * signed-in customer reads and sets their OWN choice, no one else's. Opting out
 * stops marketing (in-app + email) and excludes them from ad-platform exports.
 */
@RestController
@RequestMapping(ApiConstants.BASE_PATH + "/marketingPreference")
public class MarketingPreferenceController {

    private final MarketingPreferenceService prefs;
    private final PartyScope partyScope;

    public MarketingPreferenceController(MarketingPreferenceService prefs, PartyScope partyScope) {
        this.prefs = prefs;
        this.partyScope = partyScope;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> get() {
        return ResponseEntity.ok(Map.of("marketingOptOut", prefs.isOptedOut(requireCustomer())));
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> set(@RequestBody Map<String, Object> body) {
        boolean optOut = Boolean.TRUE.equals(body.get("optOut"))
                || "true".equalsIgnoreCase(String.valueOf(body.get("optOut")));
        boolean now = prefs.setOptOut(requireCustomer(), optOut, emailFromToken());
        return ResponseEntity.ok(Map.of("marketingOptOut", now));
    }

    /** A marketing preference belongs to the customer whose token this is. */
    private String requireCustomer() {
        return partyScope.scopedPartyId().orElseThrow(() ->
                new BadRequestException("marketing preferences are a customer self-service setting"));
    }

    private String emailFromToken() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
            return jwt.getClaimAsString("email");
        }
        return null;
    }
}
