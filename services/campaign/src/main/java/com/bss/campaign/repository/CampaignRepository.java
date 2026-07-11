package com.bss.campaign.repository;

import com.bss.campaign.entity.Campaign;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CampaignRepository extends JpaRepository<Campaign, String> {

    Optional<Campaign> findByIdAndTenantId(String id, String tenantId);

    List<Campaign> findByTenantId(String tenantId);

    List<Campaign> findByTenantIdAndStatusAndTriggerEventType(
            String tenantId, String status, String triggerEventType);
}
