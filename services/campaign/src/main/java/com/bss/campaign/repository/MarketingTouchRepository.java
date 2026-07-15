package com.bss.campaign.repository;

import com.bss.campaign.entity.MarketingTouch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;

@Repository
public interface MarketingTouchRepository extends JpaRepository<MarketingTouch, String> {

    long countByTenantIdAndPartyIdAndSentAtAfter(String tenantId, String partyId, OffsetDateTime after);
}
