package com.bss.som.repository;

import com.bss.som.entity.StarterKit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StarterKitRepository extends JpaRepository<StarterKit, String> {
    Optional<StarterKit> findByTenantIdAndActivationCode(String tenantId, String code);
    Optional<StarterKit> findFirstByTenantIdAndProductOrderId(String tenantId, String productOrderId);
    List<StarterKit> findTop200ByTenantIdAndDealerOrgIdOrderByCreatedAtDesc(String tenantId, String dealerOrgId);
}
