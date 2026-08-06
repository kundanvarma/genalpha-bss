package com.bss.qualification.repository;

import com.bss.qualification.entity.LegacyServiceQualification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LegacyServiceQualificationRepository
        extends JpaRepository<LegacyServiceQualification, String> {

    List<LegacyServiceQualification> findTop100ByTenantIdOrderByCreatedAtDesc(String tenantId);

    Optional<LegacyServiceQualification> findByIdAndTenantId(String id, String tenantId);
}
