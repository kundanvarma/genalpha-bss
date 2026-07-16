package com.bss.som.repository;

import com.bss.som.entity.DealerAgreement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DealerAgreementRepository extends JpaRepository<DealerAgreement, String> {
    Optional<DealerAgreement> findByTenantIdAndDealerOrgId(String tenantId, String dealerOrgId);
    List<DealerAgreement> findByTenantIdAndClientId(String tenantId, String clientId);
    List<DealerAgreement> findByTenantIdOrderByName(String tenantId);
}
