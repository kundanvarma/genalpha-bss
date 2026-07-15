package com.bss.quote.repository;

import com.bss.quote.entity.SalesOpportunity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SalesOpportunityRepository extends JpaRepository<SalesOpportunity, String> {

    List<SalesOpportunity> findByTenantIdOrderByCreatedAtDesc(String tenantId);

    Optional<SalesOpportunity> findByIdAndTenantId(String id, String tenantId);
}
