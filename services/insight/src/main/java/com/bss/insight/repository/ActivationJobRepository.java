package com.bss.insight.repository;

import com.bss.insight.entity.ActivationJob;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ActivationJobRepository extends JpaRepository<ActivationJob, String> {

    Optional<ActivationJob> findByIdAndTenantId(String id, String tenantId);
}
