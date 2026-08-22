package com.bss.usage.repository;

import com.bss.usage.entity.PendingDataReward;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PendingDataRewardRepository extends JpaRepository<PendingDataReward, String> {
    List<PendingDataReward> findByTenantIdAndPartyId(String tenantId, String partyId);
}
