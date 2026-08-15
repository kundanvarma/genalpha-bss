package com.bss.communication.repository;

import com.bss.communication.entity.MarketingOptOut;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MarketingOptOutRepository extends JpaRepository<MarketingOptOut, String> {

    boolean existsByTenantIdAndPartyId(String tenantId, String partyId);

    void deleteByTenantIdAndPartyId(String tenantId, String partyId);
}
