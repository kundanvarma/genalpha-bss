package com.bss.insight.service;

import com.bss.insight.entity.PartyTrait;
import com.bss.insight.repository.PartyTraitRepository;
import com.bss.insight.security.TenantScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The BSS-native feature store's write side: BSS operational events become
 * customer traits. Idempotent per (party, key, value) so re-delivery is safe —
 * at-least-once in, exactly-one row out.
 */
@Service
public class PartyTraitService {

    private final PartyTraitRepository traits;
    private final TenantScope tenantScope;

    public PartyTraitService(PartyTraitRepository traits, TenantScope tenantScope) {
        this.traits = traits;
        this.tenantScope = tenantScope;
    }

    @Transactional
    public void upsert(String partyId, String key, String value) {
        if (partyId == null || partyId.isBlank() || value == null || value.isBlank()) {
            return;
        }
        String tenantId = tenantScope.currentTenantId();
        if (traits.existsByTenantIdAndPartyIdAndTraitKeyAndTraitValue(tenantId, partyId, key, value)) {
            return;
        }
        PartyTrait t = new PartyTrait();
        t.setId(UUID.randomUUID().toString());
        t.setTenantId(tenantId);
        t.setPartyId(partyId);
        t.setTraitKey(key);
        t.setTraitValue(value);
        t.setUpdatedAt(OffsetDateTime.now());
        traits.save(t);
    }
}
