package com.bss.campaign.repository;

import com.bss.campaign.entity.CampaignExecution;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CampaignExecutionRepository extends JpaRepository<CampaignExecution, String> {

    boolean existsByTenantIdAndCampaignIdAndPartyId(String tenantId, String campaignId, String partyId);

    List<CampaignExecution> findByTenantIdAndCampaignId(String tenantId, String campaignId);

    java.util.List<com.bss.campaign.entity.CampaignExecution>
            findByTenantIdAndPartyIdAndConvertedAtIsNull(String tenantId, String partyId);
}
