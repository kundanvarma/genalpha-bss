package com.bss.entitlement.service;

import com.bss.entitlement.entity.EntitlementToken;
import com.bss.entitlement.repository.EntitlementTokenRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Optional;

/** The token a phone keeps after EAP-AKA (TS.43 "Token"): opaque, per
 * subscriber and device, valid for a configured while, revocable. */
@Service
public class TokenService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final EntitlementTokenRepository tokens;
    private final long validitySeconds;

    public TokenService(EntitlementTokenRepository tokens,
            @Value("${bss.entitlement.token-validity-seconds:2592000}") long validitySeconds) {
        this.tokens = tokens;
        this.validitySeconds = validitySeconds;
    }

    public long validitySeconds() {
        return validitySeconds;
    }

    @Transactional
    public EntitlementToken issue(String tenantId, String imsi, String terminalId) {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        EntitlementToken t = new EntitlementToken();
        t.setToken(Base64.getUrlEncoder().withoutPadding().encodeToString(b));
        t.setTenantId(tenantId);
        t.setImsi(imsi);
        t.setTerminalId(terminalId);
        t.setIssuedAt(OffsetDateTime.now());
        t.setExpiresAt(OffsetDateTime.now().plusSeconds(validitySeconds));
        return tokens.save(t);
    }

    @Transactional(readOnly = true)
    public Optional<EntitlementToken> resolve(String tenantId, String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return tokens.findByTokenAndTenantId(token, tenantId)
                .filter(t -> t.getExpiresAt().isAfter(OffsetDateTime.now()));
    }

    /** Revoke every token of a subscriber (a SIM swap, a termination). */
    @Transactional
    public int revokeAll(String tenantId, String imsi) {
        var list = tokens.findByTenantIdAndImsi(tenantId, imsi);
        tokens.deleteAll(list);
        return list.size();
    }

    @Scheduled(fixedDelayString = "${bss.entitlement.token-sweep-ms:3600000}")
    @Transactional
    public void sweep() {
        try (com.bss.entitlement.security.TenantContext ignored = com.bss.entitlement.security.TenantContext.actAsSystem()) {
            tokens.deleteByExpiresAtBefore(OffsetDateTime.now());
        }
    }
}
