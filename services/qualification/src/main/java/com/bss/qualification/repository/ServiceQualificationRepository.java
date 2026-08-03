package com.bss.qualification.repository;

import com.bss.qualification.entity.ServiceQualification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ServiceQualificationRepository extends JpaRepository<ServiceQualification, String> {

    Optional<ServiceQualification> findByIdAndTenantId(String id, String tenantId);

    List<ServiceQualification> findTop100ByTenantIdOrderByCreatedAtDesc(String tenantId);
}
