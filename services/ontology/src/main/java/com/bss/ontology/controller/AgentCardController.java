package com.bss.ontology.controller;

import com.bss.ontology.api.ApiConstants;
import com.bss.ontology.dto.A2aMessages.AgentCard;
import com.bss.ontology.security.TenantScope;
import com.bss.ontology.service.AgentCardService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * The A2A Agent Card at the protocol's well-known path. Anonymous by the
 * protocol's design — an agent reads it before it has a token, to learn where
 * to get one — and declared in ops/security/public-paths.txt. It names skills,
 * an endpoint and an issuer; no data of anyone's. The card differs per tenant
 * (hostname) and per origin, so a shared cache must key on both.
 */
@RestController
public class AgentCardController {

    private final AgentCardService cards;
    private final TenantScope tenantScope;

    public AgentCardController(AgentCardService cards, TenantScope tenantScope) {
        this.cards = cards;
        this.tenantScope = tenantScope;
    }

    @GetMapping(value = ApiConstants.AGENT_CARD, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AgentCard> card(HttpServletRequest request) {
        AgentCard card = cards.card(tenantScope.currentTenantId(), request);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic())
                .varyBy("X-Tenant-Id", "X-Forwarded-Host", "X-Forwarded-Proto")
                .body(card);
    }
}
