package com.bss.campaign.repository;

import com.bss.campaign.entity.ReferralConversion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface ReferralConversionRepository extends JpaRepository<ReferralConversion, String> {
    Optional<ReferralConversion> findByTenantIdAndJoinerPartyId(String tenantId, String joinerPartyId);
    List<ReferralConversion> findByTenantIdAndCode(String tenantId, String code);
    List<ReferralConversion> findByTenantId(String tenantId);
    long countByTenantIdAndReferrerPartyIdAndCreatedAtAfter(
            String tenantId, String referrerPartyId, OffsetDateTime after);
    long countByTenantIdAndAreaCode(String tenantId, String areaCode);
}
