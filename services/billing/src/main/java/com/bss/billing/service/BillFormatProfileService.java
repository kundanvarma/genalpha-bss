package com.bss.billing.service;

import com.bss.billing.entity.BillFormatProfile;
import com.bss.billing.exception.NotFoundException;
import com.bss.billing.repository.BillFormatProfileRepository;
import com.bss.billing.security.TenantScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    public List<Map<String, Object>> findAll() {
        return profiles.findByTenantIdOrderByCode(tenantScope.currentTenantId())
                .stream().map(this::toMap).toList();
    }

    @Transactional
    public Map<String, Object> upsert(String code, Map<String, Object> dto) {
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
        if (dto.get("name") != null) {
            profile.setName(String.valueOf(dto.get("name")));
        }
        if (dto.get("syntax") != null) {
            String syntax = String.valueOf(dto.get("syntax"));
            if (!java.util.Set.of("ubl", "cii", "edifact", "facturx").contains(syntax)) {
                throw new com.bss.billing.exception.BadRequestException(
                        "syntax is one of: ubl, cii (the EN 16931 syntaxes), edifact"
                        + " (INVOIC segments), facturx (CII embedded in the PDF)");
            }
            profile.setSyntax(syntax);
        }
        if (dto.containsKey("customizationId")) {
            profile.setCustomizationId(dto.get("customizationId") == null
                    ? null : String.valueOf(dto.get("customizationId")));
        }
        if (dto.containsKey("profileId")) {
            profile.setProfileId(dto.get("profileId") == null
                    ? null : String.valueOf(dto.get("profileId")));
        }
        if (dto.get("paymentReference") != null) {
            profile.setPaymentReference(Boolean.parseBoolean(String.valueOf(dto.get("paymentReference"))));
        }
        profile.setLastUpdate(OffsetDateTime.now());
        return toMap(profiles.save(profile));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> findByCode(String code) {
        return profiles.findByTenantIdAndCode(tenantScope.currentTenantId(), code)
                .map(this::toMap)
                .orElseThrow(() -> NotFoundException.forResource("BillFormatProfile", code));
    }

    private Map<String, Object> toMap(BillFormatProfile p) {
        Map<String, Object> map = new LinkedHashMap<>();
        // the code IS the public identity (the tenant's format points at it)
        map.put("id", p.getCode());
        map.put("code", p.getCode());
        map.put("name", p.getName());
        map.put("syntax", p.getSyntax());
        map.put("customizationId", p.getCustomizationId());
        map.put("profileId", p.getProfileId());
        map.put("paymentReference", p.isPaymentReference());
        map.put("lastUpdate", p.getLastUpdate() == null ? null : p.getLastUpdate().toString());
        map.put("@type", "BillFormatProfile");
        return map;
    }
}
