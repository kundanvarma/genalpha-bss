package com.bss.agreement.repository;

import com.bss.agreement.entity.PartnershipType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PartnershipTypeRepository extends JpaRepository<PartnershipType, String> {

    List<PartnershipType> findByTenantIdOrderByCreatedAtDesc(String tenantId);

    Optional<PartnershipType> findByIdAndTenantId(String id, String tenantId);
}
