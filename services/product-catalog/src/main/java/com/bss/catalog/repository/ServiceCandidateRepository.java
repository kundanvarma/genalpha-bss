package com.bss.catalog.repository;

import com.bss.catalog.entity.ServiceCandidate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ServiceCandidateRepository extends JpaRepository<ServiceCandidate, String> {

    Optional<ServiceCandidate> findByIdAndTenantId(String id, String tenantId);
}
