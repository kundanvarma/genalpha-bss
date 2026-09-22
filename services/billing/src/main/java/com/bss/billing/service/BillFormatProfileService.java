package com.bss.billing.service;

import com.bss.billing.dto.BillFormatProfileRequest;
import com.bss.billing.dto.BillFormatProfileView;
import com.bss.billing.entity.BillFormatProfile;
import com.bss.billing.exception.NotFoundException;
import com.bss.billing.repository.BillFormatProfileRepository;
import com.bss.billing.security.TenantScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Format profiles as CONFIG ROWS: what a country's e-invoice profile is
 * — syntax, CustomizationID, ProfileID, payment-reference rule — read
 * and edited live by the tenant's admin. Adding a country is an insert
 * here, not a deploy; the tenant's distribution format picks a row by
 * code, and the renderer follows the row.
 */
@Service
public class BillFormatProfileService {

    private final BillFormatProfileRepository profiles;
    private final TenantScope tenantScope;

    public BillFormatProfileService(BillFormatProfileRepository profiles, TenantScope tenantScope) {
        this.profiles = profiles;
        this.tenantScope = tenantScope;
    }

    @Transactional(readOnly = true)
    public List<BillFormatProfileView> findAll() {
        return profiles.findByTenantIdOrderByCode(tenantScope.currentTenantId())
                .stream().map(this::toView).toList();
    }

    /** Merge: absent leaves a field alone; an explicit null clears the ids. */
    @Transactional
    public BillFormatProfileView upsert(String code, BillFormatProfileRequest dto) {
        String tenant = tenantScope.currentTenantId();
        BillFormatProfile profile = profiles.findByTenantIdAndCode(tenant, code)
                .orElseGet(() -> {
                    BillFormatProfile fresh = new BillFormatProfile();
                    fresh.setId(UUID.randomUUID().toString());
                    fresh.setTenantId(tenant);
                    fresh.setCode(code);
                    fresh.setName(code);
                    fresh.setSyntax("ubl");
                    return fresh;
                });
        if (dto.name() != null) {
            profile.setName(dto.name());
        }
        if (dto.syntax() != null) {
            String syntax = dto.syntax();
            if (!java.util.Set.of("ubl", "cii", "edifact", "facturx").contains(syntax)) {
                throw new com.bss.billing.exception.BadRequestException(
                        "syntax is one of: ubl, cii (the EN 16931 syntaxes), edifact"
                        + " (INVOIC segments), facturx (CII embedded in the PDF)");
            }
            profile.setSyntax(syntax);
        }
        if (BillFormatProfileRequest.given(dto.customizationId())) {
            profile.setCustomizationId(BillFormatProfileRequest.textOf(dto.customizationId()));
        }
        if (BillFormatProfileRequest.given(dto.profileId())) {
            profile.setProfileId(BillFormatProfileRequest.textOf(dto.profileId()));
        }
        if (dto.paymentReference() != null) {
            profile.setPaymentReference(dto.paymentReference());
        }
        profile.setLastUpdate(OffsetDateTime.now());
        return toView(profiles.save(profile));
    }

    @Transactional(readOnly = true)
    public BillFormatProfileView findByCode(String code) {
        return profiles.findByTenantIdAndCode(tenantScope.currentTenantId(), code)
                .map(this::toView)
                .orElseThrow(() -> NotFoundException.forResource("BillFormatProfile", code));
    }

    private BillFormatProfileView toView(BillFormatProfile p) {
        // the code IS the public identity (the tenant's format points at it)
        return new BillFormatProfileView(p.getCode(), p.getCode(), p.getName(), p.getSyntax(),
                p.getCustomizationId(), p.getProfileId(), p.isPaymentReference(),
                p.getLastUpdate() == null ? null : p.getLastUpdate().toString(), "BillFormatProfile");
    }
}
