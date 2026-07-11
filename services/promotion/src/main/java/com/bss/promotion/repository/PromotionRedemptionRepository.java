package com.bss.promotion.repository;

import com.bss.promotion.entity.PromotionRedemption;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PromotionRedemptionRepository extends JpaRepository<PromotionRedemption, String> {

    List<PromotionRedemption> findByTenantIdAndOwnerPartyId(String tenantId, String ownerPartyId);

    boolean existsByTenantIdAndOwnerPartyIdAndPromotionId(String tenantId, String ownerPartyId, String promotionId);
}
