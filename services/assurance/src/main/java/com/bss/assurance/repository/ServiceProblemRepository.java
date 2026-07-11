package com.bss.assurance.repository;

import com.bss.assurance.entity.ServiceProblem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ServiceProblemRepository extends JpaRepository<ServiceProblem, String> {

    Optional<ServiceProblem> findByIdAndTenantId(String id, String tenantId);

    List<ServiceProblem> findByTenantIdAndStatus(String tenantId, String status);

    Optional<ServiceProblem> findFirstByTenantIdAndAffectedObjectAndStatus(
            String tenantId, String affectedObject, String status);
}
