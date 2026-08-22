package com.bss.campaign.repository;

import com.bss.campaign.entity.ReferralCode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ReferralCodeRepository extends JpaRepository<ReferralCode, ReferralCode.Key> {
    Optional<ReferralCode> findByTenantIdAndReferrerPartyId(String tenantId, String referrerPartyId);
    Optional<ReferralCode> findByTenantIdAndCode(String tenantId, String code);
}
