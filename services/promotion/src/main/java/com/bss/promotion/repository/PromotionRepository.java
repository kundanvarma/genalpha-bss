package com.bss.promotion.repository;

import com.bss.promotion.entity.Promotion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PromotionRepository extends JpaRepository<Promotion, String> {

    Optional<Promotion> findByIdAndTenantId(String id, String tenantId);

    Optional<Promotion> findByTenantIdAndCodeIgnoreCase(String tenantId, String code);
}
