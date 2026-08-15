package com.bss.communication.service;

import com.bss.communication.entity.MarketingOptOut;
import com.bss.communication.entity.Suppression;
import com.bss.communication.events.DomainEventPublisher;
import com.bss.communication.repository.MarketingOptOutRepository;
import com.bss.communication.repository.SuppressionRepository;
import com.bss.communication.security.TenantScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * The customer's marketing preference — the compliance spine behind the send
 * path. Opting OUT writes a party-keyed row (the send loop skips it, in-app and
 * email) AND, when the address is known, suppresses it + broadcasts
 * EmailSuppressedEvent so ad-platform activations exclude them too. Opting back
 * in removes the party row; the address suppression is deliberately left (a
 * conservative choice — re-consent to third-party ad targeting is its own act).
 */
@Service
public class MarketingPreferenceService {

    private final MarketingOptOutRepository optOuts;
    private final SuppressionRepository suppressions;
    private final DomainEventPublisher events;
    private final TenantScope tenantScope;

    public MarketingPreferenceService(MarketingOptOutRepository optOuts, SuppressionRepository suppressions,
            DomainEventPublisher events, TenantScope tenantScope) {
        this.optOuts = optOuts;
        this.suppressions = suppressions;
        this.events = events;
        this.tenantScope = tenantScope;
    }

    @Transactional(readOnly = true)
    public boolean isOptedOut(String partyId) {
        return optOuts.existsByTenantIdAndPartyId(tenantScope.currentTenantId(), partyId);
    }

    /** Set the preference; returns the resulting opted-out state. {@code email}
     * may be null (e.g. a one-click link that only carries the party). */
    @Transactional
    public boolean setOptOut(String partyId, boolean optOut, String email) {
        String tenant = tenantScope.currentTenantId();
        if (!optOut) {
            optOuts.deleteByTenantIdAndPartyId(tenant, partyId);
            return false;
        }
        if (!optOuts.existsByTenantIdAndPartyId(tenant, partyId)) {
            MarketingOptOut o = new MarketingOptOut();
            o.setId(UUID.randomUUID().toString());
            o.setTenantId(tenant);
            o.setPartyId(partyId);
            o.setReason("self-service");
            o.setCreatedAt(OffsetDateTime.now());
            optOuts.save(o);
        }
        if (email != null && !email.isBlank() && !suppressions.existsByTenantIdAndEmail(tenant, email)) {
            Suppression s = new Suppression();
            s.setId(UUID.randomUUID().toString());
            s.setTenantId(tenant);
            s.setEmail(email.trim().toLowerCase());
            s.setReason("unsubscribe");
            s.setCreatedAt(OffsetDateTime.now());
            suppressions.save(s);
            events.publish("EmailSuppressedEvent", "suppression",
                    Map.of("email", s.getEmail(), "reason", "unsubscribe"), tenant);
        }
        return true;
    }
}
